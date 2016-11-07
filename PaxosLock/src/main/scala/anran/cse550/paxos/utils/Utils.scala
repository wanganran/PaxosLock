package anran.cse550.paxos.utils

import com.typesafe.config.ConfigFactory

/**
 * Created by wanganran on 16/10/27.
 */
object Utils {
  private var _lastGUID: String = null
  private val debug=ConfigFactory.load().getBoolean("paxos.debug")

  def lastGUID = _lastGUID

  def getGUID = {
    _lastGUID = java.util.UUID.randomUUID().toString
    _lastGUID
  }

  def compareGUID(a: String, b: String) = java.util.UUID.fromString(a).compareTo(java.util.UUID.fromString(b))

  def Err(id:Int, s: String) = {
    System.out.println("#"+id+" Error: " + s)
  }

  def Debug(id:Int, s: String) = if(debug){
    System.out.println("#"+id+" Debug: " + s)
  }

  def Warn(id:Int, s: String) = {
    System.out.println("#"+id+" Warn: " + s)
  }

  def mapOp[A,B](map:Map[A,B], op:(B,B)=>B, key:A, inc:B)={
    Debug(-1, key+"+="+inc)
    if(!map.contains(key))map+(key->inc)
    else map+(key->op(map(key), inc))
  }
}
