package anran.cse550.paxos.test

import akka.actor.{PoisonPill, Props, ActorSystem}
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
object SequentialTest {
  def main(args:Array[String]): Unit ={
    val N=5
    val actors=scala.collection.mutable.IndexedSeq((1 to N) map {ServerActor.getActor(_)}:_*)

    implicit  val timeout=akka.util.Timeout(5 second)
    Thread.sleep(1000)

    val T=20
    var groundTruth=List[(String, String ,LockAction)]()
    //random actor destination, random lock, sequential
    val res=for(t<-1 to T) yield {
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

    //output all entries to verify if all servers have the same data
    Thread.sleep(1000)
    println("\n==========Groundtruth==========")
    println("VarName\tAction\tResult\tGUID")
    for(i<-0 until T)
      println(groundTruth(i)._2+"\t\t"+groundTruth(i)._3+"\t"+res(i)+"\t"+ groundTruth(i)._1)
    println("===============================")
    for(i<-0 until N) {
      Thread.sleep(100)
      actors(i) ! OutputAllEntries
    }
  }
}
