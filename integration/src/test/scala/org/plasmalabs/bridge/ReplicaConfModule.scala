package org.plasmalabs.bridge

trait ReplicaConfModule extends CommonSetupModule {

  def clientsConfString(replicaCount: Int) =
    (for {
      clientId <- 0 until replicaCount
    } yield s"""
|        ${2 * clientId} = {
|          publicKeyFile = "clientPublicKey${2 * clientId}.pem"
|          host = "localhost"
|          port = ${clientPort + 2 * clientId}
|          secure = "false"
|        }
|        ${2 * clientId + 1} = {
|          publicKeyFile = "consensusPublicKey${clientId}.pem"
|          host = "localhost"
|          port = ${clientPort + 2 * clientId + 1}
|          secure = "false"
|        }""").mkString("\n")

  def consensusConfString(replicaId: Int, replicaCount: Int) = s"""
|bridge {
|  replica {
|    # the unique number that identifies this replica
|    # replical ids are consecutive numbers starting from 0
|    replicaId = ${replicaId}
|    # the unique number that identifies this client
|    # client ids for replicas are odd numbers starting from 1
|    clientId = ${2 * replicaId + 1}
|    requests {
|      # the host where we are listening for requests
|      host = "[::]"
|      # the port where we are listening for requests
|      port = ${requestPort + replicaId}
|    } 
|    responses {
|      # the host where we are listening for responses
|      host = "[::]"
|      # the port where we are listening for responses
|      port = ${clientPort + 2 * replicaId + 1}
|    } 
|    # security configuration
|    security {
|      # path to the public key file
|      publicKeyFile = "consensusPublicKey${replicaId}.pem"
|      # path to the private key file
|      privateKeyFile = "consensusPrivateKey${replicaId}.pem"
|    }
|    consensus {
|      replicaCount = ${replicaCount}
|      # map mapping each replica to its corresponding backend
|      replicas = {
${replicasConfString(replicaCount)}
|      }
|    }
|    clients {
|      clientCount = ${2 * replicaCount}
|      # map mapping each client to its corresponding client
|      clients = {
${clientsConfString(replicaCount)}
|      }
|    monitor = { 
|       client = {
|         # in seconds
|         primaryResponseWait = 10 
|         # in seconds
|         otherReplicasResponseWait = 10 
|         # in seconds
|
|         retryPolicy =  {
|           # in seconds
|           initialDelay = 1
|           maxRetries = 1
|           delayMultiplier = 1
|         }
|       }
|     }
|     pbftInternal = {
|       retryPolicy = {
|         # in seconds
|         initialDelay = 1 
|         # in seconds
|         maxRetries = 1
|         delayMultiplier = 1
|       }
|     }
|    }
|  }
|}
  """.trim().stripMargin

}
