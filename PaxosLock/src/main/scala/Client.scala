import akka.actor.{Props, ActorSystem, Actor}
import anran.cse550.paxos.Structs.{Response, Lock, Unlock, Request}
import anran.cse550.paxos.server.ServerActor

import akka.pattern.ask
import anran.cse550.paxos.utils.Utils
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

import scala.concurrent.Await

/**
 * Created by wanganran on 16/11/4.
 */
object Client {
  implicit val timeout=akka.util.Timeout(1 second)
  var host="127.0.0.1"
  var port=0
  var id=0
  class ClientActor extends Actor{
    val server=context.actorSelection("akka.tcp://paxosSystem@"+host+":"+port+"/user/PaxosServer"+id)
    def receive()={
      case (varName:String, action:String)=>{
        val res=server ? Request(Utils.getGUID, varName, if(action.equals("lock"))Lock else Unlock)
        sender ! Await.result(res, 1 second).asInstanceOf[Response].success
      }
    }

  }
  def main(args:Array[String]): Unit ={
    id=args(0).toInt
    val config = ConfigFactory.load()
    host=config.getString("paxos.serverIp" + id)
    port=config.getInt("paxos.serverPort" + id)

    val actor=ServerActor.getSystem(config.getString("paxos.clientIp"), config.getInt("paxos.clientPort")).actorOf(Props[ClientActor])
    while(true) {
      val cmd = scala.io.StdIn.readLine().split(" ")
      if (cmd.length < 2) {
        println("Wrong command!")
      }
      else if (cmd(0).toLowerCase != "lock" && cmd(0).toLowerCase() != "unlock") {
        println("Wrong command!")
      }
      else {
        val res=actor ?(cmd(1), cmd(0).toLowerCase)
        if(Await.result(res, 1 second).asInstanceOf[Boolean])
          println("Success!")
        else println("Failed!")
      }
    }
  }
}
