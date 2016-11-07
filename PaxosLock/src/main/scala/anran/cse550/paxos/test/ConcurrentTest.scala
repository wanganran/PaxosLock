package anran.cse550.paxos.test

import anran.cse550.paxos.Structs._
import anran.cse550.paxos.server.ServerActor

import akka.pattern.ask
import anran.cse550.paxos.utils.Utils
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created by wanganran on 16/11/3.
 */
object ConcurrentTest {

  def main(args:Array[String]): Unit ={
    val N=5
    val actors=(1 to N) map {ServerActor.getActor(_)}

    implicit  val timeout=akka.util.Timeout(30 second) //timeout should be longer
    Thread.sleep(1000)

    val T=5
    var groundTruth=List[(String, String ,LockAction)]()
    //random actor destination, random lock, parallel
    val resFut= for(t<-1 to T) yield {
      val r=Random.nextInt(N)
      val l=if(Random.nextBoolean()) Lock else Unlock
      println("Round "+t+": "+"server "+(r+1)+", action: "+l)
      val guid=Utils.getGUID
      groundTruth:+=(guid, "a", l)
      actors(r) ? Request(guid, "a", l)
    }
    val result = Future.sequence(resFut)
    val res=Await.result(result, 30 second)

    //check the entries are consistent

    Thread.sleep(1000)
    println("\n==========Groundtruth==========")
    println("VarName\tAction\tResult\tGUID")
    for(i<-0 until T)
      println(groundTruth(i)._2+"\t\t"+groundTruth(i)._3+"\t"+res(i).asInstanceOf[Response].success+"\t"+groundTruth(i)._1)
    println("===============================")
    for(i<-0 until N) {
      Thread.sleep(100)
      actors(i) ! OutputAllEntries
    }

  }
}
