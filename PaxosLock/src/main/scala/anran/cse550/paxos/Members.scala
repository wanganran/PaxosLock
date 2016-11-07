package anran.cse550.paxos

import anran.cse550.paxos.utils.Utils
import com.typesafe.config.ConfigFactory
import Utils.Err

/**
 * Created by wanganran on 16/10/29.
 */
class Members (members: Array[(Int, String, Int)]) {
  assert(members.size>1)
  val memberEndpoints=members.tail
  val selfEndpoint=members.head
  val memberCount=members.size
}

object Members{
  lazy val predefinedMembers=(()=> {
    try {
      val config = ConfigFactory.load()
      val memberCount = config.getInt("paxos.serverCount")
      (1 to memberCount) map { n => (n, config.getString("paxos.serverIp" + n), config.getInt("paxos.serverPort" + n)) } toArray
    }
    catch {
      case ex: Exception =>
        Err(-1, "Config error! " + ex.getMessage)
        Array.empty[(Int, String, Int)]
    }
  })()
  def getPredefinedMembers(selfId:Int)={
    if(predefinedMembers.length<1) null else
    if(selfId>predefinedMembers.length) throw new IndexOutOfBoundsException("Node ID exceeds total nodes number.")
    val members=predefinedMembers.clone()
    val m=members(selfId-1)
    members(selfId-1)=members(0)
    members(0)=m
    new Members(members)
  }
}