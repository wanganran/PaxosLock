package anran.cse550.paxos.server

import java.util.concurrent.TimeUnit

import akka.remote.RemoteScope
import anran.cse550.paxos.utils.Utils
import anran.cse550.paxos.{Members, State, Structs}
import anran.cse550.paxos.Structs._
import Utils.Err
import Utils.Debug
import Utils.mapOp
import akka.actor._
import akka.pattern.ask
import com.typesafe.config.ConfigFactory
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.collection.immutable.{Stack, Queue}

object ServerActor {
  def getSystem(ip:String, port:Int)=
    ActorSystem("paxosSystem", ConfigFactory.parseString("""
    akka {
       actor {
           warn-about-java-serializer-usage = false
           provider = "akka.remote.RemoteActorRefProvider"
             }
       remote {
           transport = ["akka.remote.netty.tcp"]
       netty.tcp {
           hostname = """+ip+"""
           port = """+port+"""
                 }
             }
        }"""))

  def getActor(selfId: Int) = {
    val members= Members.getPredefinedMembers(selfId)
    val selfEndpoint = members.selfEndpoint
    val system=getSystem(selfEndpoint._2, selfEndpoint._3)
    Debug(selfId, "Members: " + members.selfEndpoint.toString() + members.memberEndpoints.map(", " + _.toString()).foldLeft("")(_+_))
    system.actorOf(Props(new ServerActor(selfId, members)), "PaxosServer"+selfId)
  }
}

class ServerActor(selfId:Int, members:Members) extends Actor {
  var state=State.newState
  Debug(selfId, "ServerActor init")
  //class members
  private val timeout=ConfigFactory.load().getInt("paxos.timeout")
  Debug(selfId, "Timeout set to "+timeout)
  private implicit val _timeout=akka.util.Timeout(timeout, TimeUnit.MILLISECONDS)

  private lazy val otherServers=members.memberEndpoints.map{
    case (id, host, port)=>
      context.actorSelection("akka.tcp://paxosSystem@"+host+":"+port+"/user/PaxosServer"+id)
  }

  var processing=false

  //queue the request when last request is processing
  var requestQueue=Stack[(Request, ActorRef)]()

  //used for rewrite a slot
  var beforeLockCache=Map[Int, Boolean]() //true if it is already locked before the entry is executed
  var currentLocks=Set[String]()
  var currentLockSlot= 0 //last slot that has been performed

  def undoTill(untilSlotId:Int):Set[Int]={
    var res=Set[Int]()
    for (i<-currentLockSlot until untilSlotId by -1){
      if(!state.lockEntries.contains(i) || state.lockEntries(i).isInstanceOf[PrepareEntry])
        res+=i
    }
    if(res.isEmpty){ //every slot is OK, do undo
      for (i<-currentLockSlot until untilSlotId by -1){
        val lastres=beforeLockCache(i)
        state.lockEntries(i) match {
          case LockEntry(_,_, LockContent(_, varName, action))=>{
            Debug(selfId, "Undo: "+i+" slot, "+varName+": "+ (action==Lock) + "->"+lastres)
            beforeLockCache-=i
            if(currentLocks.contains(varName) && !lastres)
              currentLocks-=varName
            else if(!currentLocks.contains(varName) && lastres)
              currentLocks+=varName
          }
          case _ =>
        }
      }
      currentLockSlot=untilSlotId
    }
    res
  }

  def performLock(toSlotId:Int): Either[Set[Int], Boolean] = {
    var res=Set[Int]()
    if(toSlotId<currentLockSlot){
      //do the undo
      val e=undoTill(toSlotId)
      if(e.nonEmpty)return Left(e)
      //otherwise go to the next if
    }
    if(toSlotId==currentLockSlot) {
      //do nothing
      val res=
        if(state.lockEntries(toSlotId).asInstanceOf[LockEntry].lockContent.action==Lock) {
          if (beforeLockCache(toSlotId)) false
          else true
        }
        else true
      return Right(res)
    }
    for(i<-currentLockSlot+1 to toSlotId){
      if(!state.lockEntries.contains(i) || state.lockEntries(i).isInstanceOf[PrepareEntry])
        res+=i
    }
    if(res.isEmpty){
      var lockSucc=true
      for(i<-currentLockSlot+1 to toSlotId) {
        state.lockEntries(i) match {
          case LockEntry(_,_, LockContent(guid, varName, action)) =>
            lockSucc=true
            Debug(selfId, "Performing lock: "+i+" slot, "+varName+": "+action+" ("+guid+")")
            beforeLockCache += (i -> currentLocks.contains(varName))
            if (currentLocks.contains(varName) && action.equals(Unlock)) {
              currentLocks -= varName
            }
            else if (!currentLocks.contains(varName) && action.equals(Lock)) {
              currentLocks += varName
            }
            else if (currentLocks.contains(varName) && action.equals(Lock))
              lockSucc = false
        }
      }
      currentLockSlot=toSlotId
      Right(lockSucc)
    }
    else Left(res)
  }

  //helper functions
  private def majority(n:Int)=n>members.memberCount/2

  private def majority(map:Map[String, Int]): Option[String] ={
    val n=members.memberCount
    (for((s,cnt)<-map if cnt>n/2) yield s).headOption
  }
/*
  private def broadcast(message:Any)={
    otherServers foreach ( _ ! message)
  }
*/
  //also send to self
  private def broadcastWait(message:Any)= {
    Future.sequence(otherServers.toList.map(_ ? message).map(_.recover { case _ => Timeout }).::
      ((self ? message).recover{case _ => Timeout}))

  }

  //This method is non-blocking
  def initialize():Unit = {

    Debug(selfId, "Enter initialize() function")
    //SlotId -> {(GUID, count)}
    var responseList=Map[Int, Map[String, Int]]()

    //{(SlotId, GUID) -> Entry Item}
    var initializeCache=Map[(Int, String), LockEntry]()

    state=state.updateState(InitializingState)

    //used for rebuild the locks and cache at the beginning
    //the entries must be sequential at this time
    def rebuildLock(): Unit ={
      //clear cache
      beforeLockCache=beforeLockCache.empty
      currentLocks=currentLocks.empty
      currentLockSlot=0
      if(state.lockEntries.nonEmpty) {
        var maxSlotId = state.lockEntries.map { case (id, _) => id }.max
        performLock(maxSlotId) match {
          case Left(_) => throw new Exception("This should not happen")
          case Right(_) =>
        }
      }
    }
    def checkResponseList() = {
      responseList map {
        case (key, value) => {
          Debug(selfId, "responselist: "+key+"->"+value)
          (key, majority(value))
        }
      }
    }

    //when received responses, do the process
    def checkInitialization(): Unit = {
      val responseResult = checkResponseList()
      val beginWithArr = responseResult.filter { case (_, opt) => opt.isEmpty }.keys
      //if no entry is in an unstable state, then every entry should be merged.
      val beginWith = if (beginWithArr.isEmpty) Int.MaxValue else beginWithArr.min

      var lockEntries=state.lockEntries.empty

      val needUpdate = responseResult.filter { case (id, _) => id < beginWith }
      for ((slotId, entry) <- needUpdate) {
        assert(entry.isDefined)
        //add or replace
        lockEntries += (slotId -> initializeCache((slotId, entry.get)))
      }

      //clear all entries after beginWith
      state=state.updateEntries(lockEntries)

      //rebuild locks
      rebuildLock()

      //OK!
      state=state.updateState(StableState)
      Debug(selfId, "Ready")
    }

    //add self to response list
    for ((key, LockEntry(_, _, LockContent(guid, varName, action))) <- state.lockEntries)
      responseList += (key -> Map((guid, 1)))

    val guidToSend=Utils.getGUID
    val initFut = broadcastWait(Initialize(guidToSend, 0))

    initFut.foreach(initResp => {
      if (majority(initResp.count(_ != Timeout))) {
        for (resp <- initResp if resp != Timeout) {

          Debug(selfId, "fuck "+resp)
          resp match {
            case InitializeAck(`guidToSend`, entries) => {
              for ((slotId, entry) <- entries) {
                initializeCache += ((slotId, entry.lockContent.entryGUID) -> entry)
                if (responseList.contains(slotId)) {
                  responseList += (slotId -> mapOp[String, Int](responseList(slotId), _ + _, entry.lockContent.entryGUID, 1))
                }
                else
                  responseList += (slotId -> Map[String, Int](entry.lockContent.entryGUID->1))
              }
            }
            case _ =>
          }
        }
        checkInitialization()
      }
      else {
        //do it again
        Debug(selfId, "Cannot connect to majority of severs ("+initResp.count(_!=Timeout)+"). Reconnect after 1 second")
        Thread.sleep(1000)
        initialize()
      }
    })
  }

  //when starting running, send InternalInitialize to itself
  //self ! InternalInitialize

  private def nextSeqId(seqId:Int) ={
    (seqId/64*64+64)|(selfId&0x3f) //maximum 0x3f (63) nodes
  }

  def receive={
    //internal operations
    case InternalShutdown => {
      context.system.terminate()
    }
    case InternalInitialize => {
      Debug(selfId, "Received InternalInitialize message")
      initialize()
    }
    case OutputAllEntries =>{ //just output all the entries for verification
      println("\n===All Entries for Server #"+selfId+"===")
      println("Slot\tVarName\tAction\tGUID")
      state.lockEntries.toList.sortBy{case(slot, _)=>slot}
        .foreach { case (slot, LockEntry(_, seqId, LockContent(guid, varName, action)))=>
        println(slot+"\t\t"+varName+"\t\t"+action+"\t"+guid)
      }
      println("===============================")
    }
    //request operations
    case Initialize(msgGuid, lastSlotId) =>
      //return initializeAck containing updates since lastSlotId
      Debug(selfId, "Received Initialize("+lastSlotId+") message from "+ sender.path.name+", maxSlot="+state.getMaxSlotId)
      sender ! InitializeAck(msgGuid,
        state.lockEntries.map {
          case (a: Int, b: LockEntry) => (a, b)
          case _ => null
        }.filter {
          case (slotId, _: LockEntry) => slotId > lastSlotId
          case _ => false
        })

    case Prepare(msgGuid, slotId, seqId)=>
      Debug(selfId, "Receive Prepare message: "+slotId+", "+seqId)
      if(state.lockEntries.contains(slotId)){
        state.lockEntries(slotId) match {
          case PrepareEntry(existedSeqId) => if (existedSeqId > seqId) {
            sender ! PrepareAck(msgGuid, false, Some(existedSeqId), None)
          } else {
            state=state.insertEntry(slotId, PrepareEntry(seqId))
            sender ! PrepareAck(msgGuid, true, Some(existedSeqId), None)
          }
          case LockEntry(highestSeqId, existedSeqId, lockContent)=> if(highestSeqId > seqId) {
            sender ! PrepareAck(msgGuid, false, Some(existedSeqId), Some(lockContent))
          } else {
            state=state.insertEntry(slotId, LockEntry(seqId, existedSeqId, lockContent))
            sender ! PrepareAck(msgGuid, true, Some(existedSeqId), Some(lockContent))
          }
        }
      }
      else{
        state=state.insertEntry(slotId, PrepareEntry(seqId))
        sender ! PrepareAck(msgGuid, true, None, None)
      }

    case Accept(msgGuid, slotId, acceptedEntry)=>
      Debug(selfId, "Receive Accept message: "+slotId+", "+acceptedEntry)
      if(state.lockEntries.contains(slotId)) {
        state.lockEntries(slotId) match {
          case PrepareEntry(existedSeqId) => if (existedSeqId > acceptedEntry.seqId) { //promised not to accept it
            sender ! AcceptAck(msgGuid, false)
          } else {
            state=state.insertEntry(slotId, acceptedEntry)
            sender ! AcceptAck(msgGuid, true)
          }
          case LockEntry(highestSeqId, existedSeqId, lockContent) => if (highestSeqId > acceptedEntry.seqId) { //promised not to accept it
            sender ! AcceptAck(msgGuid, false)
          } else {
            state=state.insertEntry(slotId, acceptedEntry) //replace old //the old must not be majority
            sender ! AcceptAck(msgGuid, true)
          }
        }
      }
      else {
        sender ! AcceptAck(msgGuid, false)
      }


    //interface operations
    //this function either success in submitting and return the lock result, or crash
    case Request(msgGuid, varName, action) if state.currentState==StableState => {
      Debug(selfId, "Received Request: "+varName+", "+action+", "+processing)
      if (processing) {
        synchronized {
          requestQueue = requestQueue.push((Request(msgGuid, varName, action), sender()))
        }
      }
      else {
        def processOnce(r: Request, sender: ActorRef):Unit = r match {
          case Request(msgGuid, varName, action) => {
            Debug(selfId, "Processing Request: "+varName+", "+action)
            //block further requests
            processing = true

            //check if it already exists
            val existed=state.lockEntries.filter{ case (_,s)=>s.isInstanceOf[LockEntry]}
              .map{case (i, s)=>(i, s.asInstanceOf[LockEntry])}.
              filter {case (_, s)=>s.lockContent.entryGUID==msgGuid}

            if(existed.nonEmpty){
              val needSync=performLock(existed.head._1)
              needSync match { case Right(res)=>
                if (sender != null)
                  sender ! Response(msgGuid, varName, res)
              case Left (syncArr) =>
                //need synchronize
                synchronize(syncArr) foreach {
                  case true => {
                    //perform lock
                    performLock(existed.head._1) match {
                      case Right(res) =>
                        sender ! Response(msgGuid, varName, res)
                      case _ => throw new Exception("This won't happen.")
                    }
                  }
                  case false => throw new Exception("Synchronize failed. Maybe because of partition.")
                }
              }
              val nextRequest=synchronized {
                if (requestQueue.isEmpty) {
                  processing = false
                  null
                }
                else {
                  val res=requestQueue.top
                  requestQueue=requestQueue.pop
                  res
                }
              }
              if(nextRequest!=null)
                processOnce(nextRequest._1, nextRequest._2)
              return
            }

            val guid0 = Utils.getGUID
            //just enable a new prepare, and store the action for further use
            val nextSlotId = state.getMaxSlotId + 1

            def prepareOnce(seqId: Int, msgGuid:String, varName:String, action:LockAction, noSender:Boolean) {
              val prepare = Prepare(guid0, nextSlotId, seqId)
              Debug(selfId, "Sent Prepare: "+nextSlotId+", "+seqId)
              val prepareResult = broadcastWait(prepare)
              prepareResult foreach (result => {
                var existedSeqId = 0
                var acceptedNum = 0
                var nextProposal: LockContent = null
                result foreach {
                  case PrepareAck(`guid0`, accept, highestSeqId, highestAcceptedLock) => {
                    if (accept) {
                      acceptedNum += 1
                      if (highestAcceptedLock.isDefined) {
                        if (highestSeqId.get > existedSeqId) {
                          nextProposal = highestAcceptedLock.get
                          existedSeqId = highestSeqId.get
                        }
                      }
                    }
                  }
                  case _ =>
                }
                //existedSeqId = Math.max(existedSeqId, seqId)
                if (majority(acceptedNum)) {
                  Debug(selfId, "Majority accepted Prepare ("+nextSlotId+", "+seqId+")")
                  //accept this proposal
                  val guid1 = Utils.getGUID
                  var dontSend=false
                  val currentProposal =
                    if (nextProposal != null && nextProposal.entryGUID!=msgGuid) {
                      Debug(selfId, "Re-accept existing proposal "+nextProposal.entryGUID+", queueing "+msgGuid)
                      //need to re-submit this proposal, leave the current proposal in the next slot
                      requestQueue = requestQueue.push((Request(msgGuid, varName, action), sender))
                      dontSend=true
                      nextProposal
                    }
                    else
                      LockContent(msgGuid, varName, action)

                  val accept = Accept(guid1, nextSlotId, LockEntry(seqId, seqId, currentProposal))
                  val acceptResult = broadcastWait(accept)

                  acceptResult foreach (result => {
                    val accepted = result.map {
                      case AcceptAck(`guid1`, true) => 1
                      case _ => 0
                    }.sum
                    if (majority(accepted)) {
                      Debug(selfId, "Majority accept Accept ("+nextSlotId+", "+ seqId+", "+currentProposal+")")
                      //Consent!
                      //self is forced to accept. //TODO: verify if this has a side effect
                      state=state.insertEntry(nextSlotId, LockEntry(seqId, seqId, currentProposal))
                      //now replay the entries
                      val needSync = performLock(nextSlotId)
                      needSync match { case Right(res)=>
                        if (sender != null)
                          if(!dontSend && !noSender) sender ! Response(msgGuid, varName, res)

                      case Left (syncArr) =>
                        //need synchronize
                        synchronize(syncArr) foreach {
                          case true => {
                            //perform lock
                            performLock(nextSlotId) match {
                              case Right(res) =>
                                if(!dontSend && !noSender) sender ! Response(msgGuid, varName, res)
                              case _ => throw new Exception("This won't happen.")
                            }
                          }
                          case false => throw new Exception("Synchronize failed. Maybe because of partition.")
                        }
                      }
                      val nextRequest=synchronized {
                        if (requestQueue.isEmpty) {
                          processing = false
                          null
                        }
                        else {
                          val res=requestQueue.top
                          requestQueue=requestQueue.pop
                          res
                        }
                      }
                      if(nextRequest!=null)
                        processOnce(nextRequest._1, nextRequest._2)
                    }
                    else {
                      Debug(selfId, "Accept denied. Retry")
                      //no majority accepted. redo with a higher seq ID
                      prepareOnce(nextSeqId(seqId),currentProposal.entryGUID, currentProposal.varName,currentProposal.action, dontSend)
                    }
                  })
                }
                else {
                  Debug(selfId, "Prepare denied. Retry")
                  //need increase seq id
                  prepareOnce(nextSeqId(seqId), msgGuid, varName, action, noSender)
                }
              })
            }
            prepareOnce(nextSeqId(0), msgGuid, varName, action, false)
          }

          def synchronize(needSync: Set[Int]): Future[Boolean] = {
            if (needSync.isEmpty) Future.successful(true)
            else {
              needSync.toList.foldLeft(Future.successful(true))((fut: Future[Boolean], slotId: Int) => {
                fut flatMap {
                  case true => {
                    val msgGuid0 = Utils.getGUID
                    val prepare = Prepare(msgGuid0, slotId, nextSeqId(0))
                    val res = broadcastWait(prepare)
                    res map { result =>
                      val proposals = result.filter {
                        case PrepareAck(_, _, _, highestAcceptedLock) => {
                          if (highestAcceptedLock.isDefined) true
                          else false
                        }
                        case _ => false
                      }
                      if (proposals.isEmpty) false
                      else {
                        //check majority
                        //proposals must have lock defined
                        val maj = proposals.groupBy {
                          case PrepareAck(_, _, _, lock) => lock.get
                        }.maxBy { case (a, b) => b.size }
                        if (majority(maj._2.size)) {
                          //ok. set self
                          val majSeqId = proposals.filter {
                            case PrepareAck(_, _, _, lock) => lock.get == maj._1
                          }.map {
                            case PrepareAck(_, _, seqId, _) => seqId.get
                          }.max
                          state=state.insertEntry(slotId, LockEntry(majSeqId, majSeqId, maj._1))
                        }
                        else {
                          //no majority. This should be temporary. Just set to the highest (this won't break the rules)
                          val (seqId, lock) = proposals.maxBy {
                            case PrepareAck(_, _, highestSeqId, _) => highestSeqId.get
                          } match {
                            case PrepareAck(_, _, seqId, lock) => (seqId.get, lock.get)
                          }
                          state=state.insertEntry(slotId, LockEntry(seqId, seqId, lock))
                        }
                        true
                      }
                    }
                  }
                  case false => Future.successful(false)
                }
              })
            }
          }
        }
        processOnce(Request(msgGuid, varName, action), sender())
      }
    }
  }
}
