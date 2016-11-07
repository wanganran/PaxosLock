package anran.cse550.paxos

/**
 * Created by wanganran on 16/10/24.
 */
object Structs {
  case object Timeout
  case class Request(msgGuid:String, varName:String, action:LockAction)
  case class Response(msgGuid:String, varName:String, success:Boolean)
  case class Prepare(msgGuid:String, slotId:Int, seqId:Int)
  case class PrepareAck(msgGuid:String, accept:Boolean, highestSeqId:Option[Int], highestAcceptedLock:Option[LockContent])
  case class Accept(msgGuid:String, slotId:Int, acceptedEntry:LockEntry)
  case class AcceptAck(msgGuid:String, accept:Boolean)
  case object InternalInitialize
  case object InternalShutdown
  case object OutputAllEntries
  case class Initialize(msgGuid:String, lastSlotId:Int)
  case class InitializeAck(msgGuid:String, entries:Map[Int, LockEntry])
  case object CheckInitialization

  abstract class LockAction
  case object Lock extends LockAction
  case object Unlock extends LockAction

  case class LockContent(entryGUID:String, varName:String, action:LockAction)

  abstract class Entry
  case class PrepareEntry(seqId:Int) extends Entry
  case class LockEntry(highestSeqId:Int, seqId:Int, lockContent: LockContent) extends Entry

  abstract class AbstractState
  case object InitializingState extends AbstractState
  case object StableState extends AbstractState
}
