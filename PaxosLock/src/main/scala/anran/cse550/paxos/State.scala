package anran.cse550.paxos

import anran.cse550.paxos.Structs._

/**
 * Created by wanganran on 16/10/27.
 */
//immutable to avoid synchronization problem among threads
case class State(currentState:AbstractState,
  lockEntries:Map[Int, Entry]) { //slotId starts from 1

  def updateState(newState:AbstractState)=State(newState, lockEntries)
  //def updateLocks(newLocks:Set[String])=State(currentState, newLocks, lockEntries)
  def updateEntries(newEntries:Map[Int, Entry])=State(currentState, newEntries)

  def getMaxSlotId = if(lockEntries.isEmpty) 0 else lockEntries.keys.max
  def insertEntry(slotId:Int,newEntry:Entry)={
    updateEntries(lockEntries+(slotId->newEntry))
  }
}

object State{
  def newState=State(StableState, Map[Int, Entry]())
}
