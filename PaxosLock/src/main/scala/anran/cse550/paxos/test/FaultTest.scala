package anran.cse550.paxos.test

import akka.actor.{Props, ActorSystem}
import anran.cse550.paxos.Members
import anran.cse550.paxos.Structs._
import anran.cse550.paxos.server.ServerActor
import akka.pattern.ask
import scala.concurrent.duration._
import anran.cse550.paxos.utils.Utils

import scala.concurrent.Await
import scala.util.Random

/**
 * Created by wanganran on 16/11/3.
 */
object FaultTest {
  def main(args:Array[String]): Unit ={
    val N=5
    val actors=scala.collection.mutable.IndexedSeq((1 to N) map {ServerActor.getActor(_)}:_*)

    implicit  val timeout=akka.util.Timeout(5 second)
    Thread.sleep(1000)

    val T=5
    var groundTruth=List[(String, String ,LockAction)]()
    //random actor destination, random lock, sequential
    var res=for(t<-0 until T) yield{
      val r=Random.nextInt(N)
      val l=if(Random.nextBoolean()) Lock else Unlock
      println("Round "+t+": "+"server "+(r+1)+", action: "+l)
      val guid=Utils.getGUID
      groundTruth:+=(guid, "a", l)
      val fut = actors(r) ? Request(guid, "a", l)
      val result = Await.result(fut, 10 second)
      System.out.println("Result for round "+t+": "+result)
      result.asInstanceOf[Response].success
    }
    //shutdown the second one
    actors(1) ! InternalShutdown
    Thread.sleep(4000) //wait for shutdown
    println("===after terminate #2===")

    //another T locks

    res=res ++( for (t<-T until 2*T) yield{
      val r= (()=>{
        var rr=0
        do {
          rr = Random.nextInt(N)
        } while (rr == 1)
        rr
      })()
      val l=if(Random.nextBoolean()) Lock else Unlock
      println("Round "+t+": "+"server "+(r+1)+", action: "+l)
      val guid=Utils.getGUID
      groundTruth:+=(guid, "a", l)
      val fut = actors(r) ? Request(guid, "a", l)
      val result = Await.result(fut, 10 second)
      System.out.println("Result for round "+t+": "+result)
      result.asInstanceOf[Response].success
    })
    //restart the second one
    println("===after restart #2===")
    actors(1)=ServerActor.getActor(2)
    Thread.sleep(4000) //wait for getting ready

    //another T locks
    res=res++(for (t<-2*T until 3*T) yield{
      val r=1//Random.nextInt(N)
      val l=if(Random.nextBoolean()) Lock else Unlock
      println("Round "+t+": "+"server "+(r+1)+", action: "+l)
      val guid=Utils.getGUID
      groundTruth:+=(guid, "a", l)
      val fut = actors(r) ? Request(guid, "a", l)
      val result = Await.result(fut, 10 second)
      System.out.println("Result for round "+t+": "+result)
      result.asInstanceOf[Response].success
    })

    //output all entries to verify if all servers have the same data
    Thread.sleep(1000)
    println("\n==========Groundtruth==========")
    println("VarName\tAction\tResult\tGUID")
    for(i<-0 until 3*T)
      println(groundTruth(i)._2+"\t\t"+groundTruth(i)._3+"\t"+ res(i) +"\t"+groundTruth(i)._1)
    println("===============================")
    for(i<-0 until N) {
      Thread.sleep(100)
      actors(i) ! OutputAllEntries
    }
  }
}
