import java.util.concurrent.TimeUnit

import akka.actor._
import akka.remote.RemoteScope
import anran.cse550.paxos.server.ServerActor
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

import akka.pattern.after
import akka.dispatch.Futures

/**
 * Created by wanganran on 16/10/23.
 */
object Main {

  def main(args:Array[String]): Unit = {
    println("Server "+args(0)+" running")
    val actor=ServerActor.getActor(args(0).toInt)
  }
}
