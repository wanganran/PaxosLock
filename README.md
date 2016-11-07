# Distributed Lock Service using Paxos protocol #

Anran Wang

## The problem ##

We intend to build a distributed lock service that supports two operations by users: 

1. Lock(variableName): If a previous lock of the variableName exists, then return Failure. Otherwise, register a lock of the variable_name and return Success.
2. Unlock(variableName): Always return Success. If a previous lock of the variableName exists, remove it.

We use multiple servers to build a distributed service to tolerate fault: even if a minority of servers is down, the service should still work.

The key of such system is a consensus protocol that keeps the data stored in each server consistent. One of the protocol is the Paxos protocol. For the lock service, the protocol runs as follows:

1. Each server maintains a list of slots of either of the two operations (requests) described before.
2. Each slot has a Slot ID. We model each slot of the same Slot ID as a Paxos instance. 
3. For each incoming request, it will be assigned the next available slot by the server in its list. The server then becomes the leader to run the Paxos protocol to synchronize the slot.
4. When multiple requests are handled by different servers simultaneously, there may exist multiple leaders proposing different proposals for the same slot. The Paxos protocol will guarantee that a consensus would be eventually made in a high probability. At that time, the failed server will re-submit the accepted proposal, and move to the next available slot to re-propose its request. Note that this procedure may happen multiple times for one request (for example, A and B are two requests simultaneously handled by SA and SB. A is finally accepted by majority for Slot 1. SB will get to know this and re-submit A for Slot 1, and then propose B for Slot 2. However at that time Slot 2 are occupied by another accepted request C. SA need to re-submit A again for Slot 3.). Our system can handle this situation correctly. The server will return the result to the user as long as the request is accepted by a majority of servers.

Note that, our implementation doesn't have an "acceptor" role. This is because it seems that the acceptors are only used for load balancing the read operations. In the lock service, there is no read-only operations, and each node performs all the roles.

## Implementation ##

We implement our system using Scala programming language, mainly because it supports Actor design pattern pretty well, either locally or remotely (using Akka Actor library). Moreover, its pattern matching syntax works great for message passing.

Our system consists of two major parts. One is the State (State.scala), which stores the lock entries over time. Another is the Server Actor (server/ServerActor.scala), which receives the requests from the users, communicate with other peer servers, and send response back to the users. We can modify the configuration of the servers, e.g., the end points of peers, timeout, etc., by modifying a configuration file (src/main/resources/application.conf), without recompiling the source codes.

For the server actor implementation, specifically, we design it entirely inside an actor class. The receive function is in charge of handling received messages. There are several kind of messages. We categorize them by three types:

1. Internal messages, used for communicating with the main function.
 * InternalInitialize: when the server just starts running, or recover from a failure, the main function sends this message for initialization.
 * InternalShutdown: this message is sent by the main function to shut down the server.
 * OutputAllEntries: this message is for test purposes. When receiving this message, the server will output all the entries it have.
2. Peer messages, used for communication among the server peers.
 * Prepare(msgGuid, slotId, seqId): just as the prepare message in Paxos protocol. The msgGuid is a unique string that used to match the acknowledgements. seqId is the sequence number in Paxos protocol.
 * PrepareAck(msgGuid, accept, highestSeq, acceptedLock)： accept is whether the server accepts the corresponding Prepare message or not. acceptedLock is the existed lock accepted by server in the given slot, if any. 
 * Accept(msgGuid, slotId, acceptedEntry): acceptedEntry is the entry (i.e., the request) to be proposed. 
 * AcceptAck(msgGuid, accept): accept is whether the server accepts the corresponding Accept message or not.
 * Initialize(msgGuid, lastSlotId): it is actually a Prepare message, used to quickly catch up with other servers when a server recovers.
 * InitializeAck(msgGuid, entries): entries are all the entries with a slot ID bigger than lastSlotId in the corresponding Initialize message.
3. Interface messages, used for the users.
 * Request(msgGuid, varName, action): msgGuid is used for avoiding multiple submit due to network problems. Action is either Lock or Unlock.
 * Response(msgGuid, varName, success): success is either true or false.
 
The server works exactly as what we described in last section. There are just two points to elaborate:

1. Once the server is going to reply to the user, it will replay the entry list to know whether the lock/unlock is successful or not. We maintain a cache of the results of the first ith entry, updated when necessary.
2. When a server recovers, it will send the Initialize message to all the peers. The peers send a combined entry list back to the server. The server then select the majority for each slot. If no majority exists for a slot, it leave it blank and next time it handles a request, it will do the Prepare and eventually obtain consensus.

Our system is implemented in an entirely asynchronous way (except the tests). Hence, the server should have a good concurrent performance.

## Assumptions ##

As far as I know, our implementation only has the following assumptions:

1. Only supports a minority of servers down.
2. No Byzantine error occurs.
3. It’s hard to simulate message lost, but message lost can be modeled by server down and quickly recovers.

## How to use ##

1. First you need to install Scala and sbt.
 * If in Ubuntu, run:
   > sudo apt-get install scala
   > echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
   > sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823
   > sudo apt-get update
   > sudo apt-get install sbt
2. Go to the PaxosLock directory
3. Run "sbt run"
4. it will compile and if everything is OK it will give you multiple options.
 * Select Main will run the servers on one of the endpoints defined at src/main/resources/application.conf. Note that you need to pass a number from 1 to serverCount to the command argument to indicate the server id (e.g., run 'sbt "run 2"').  
 * Select Client will run the client. It need a parameter indicating which server you want to connect (from 1 to serverCount) (e.g., run 'sbt "run 2"'). You can input either "lock varName" or "unlock varName" and press Enter to get the result (either "Success" or "Failed").
 * Select SequentialTest will run the servers and do a lightweight test. It will simulate one client accessing the service sequentially, each time requesting a random server. At the end of the test it will output the groundtruth and the entries stored in each server and we can see that they are consistent. For simplicity, we only request for the same variable name.
 * Select FaultTest will run the servers receiving requests sequentially and Three rounds will happen: a) no faults for 5 requests; b) a server crashed, then another 5 requests; c) the server recovers, then another 5 requests. 
 * select ConcurrentTest will test the concurrent performance. It will simulate multiple clients accessing the service parallel, each client requesting a random server. Note that the groundtruth and what the server stores may be out of order. This is because the requests are sent concurrently. But the results are always consistent.
5. After it finishes, press Ctrl+C to terminate it.
6. You may find the logs starting with "DEBUG" annoying. Just editing the src/main/resources/configuration.conf and set debug to false.
7. If you want to read/modify the source code, you can install the IntellijIdea with its Scala plugin, and open the PaxosLock folder.

## Issues ##

1. If some servers are down, the progress will be made much slower since the server will assume the server still exists and need to wait until timeout when receiving messages from the failed server.
2. I’m not sure whether this case is possible in practice: a server receives a majority of Accept message for a proposal, but itself didn’t accept the proposal. If such case happens, my implementation just assumes the server itself must accept the proposal it made.
3. It may be time and bandwidth consuming when the servers have a huge numbers of entries and one server need to recover.

## Extra features ##

1. Since we assign each request with a unique GUID, we support submitting a same request multiple times due to networking issues (they will only be executed once)
2. We support server recovery, as long as majority of servers are alive. Thus, we don't need to store the entries persistently.
