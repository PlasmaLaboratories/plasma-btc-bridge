---
sidebar_position: 15
---

# Multi Signatures and Address Generation

## Why should we use Multi Signatures for our bridge? 

In the case of one bridge being hacked these funds are not lost. For our case of a m out of n Multisignature Script, five of the seven bridges need to agree on claiming funds so one bridge alone won't be able to steal the funds. 

### Escrow Addresses
This is valid for our escrow addresses: The bitcoin addresses users get from the Start-Session-Request when trying to bridge funds into the Plasma ecosystem are created with a multisig. After the user deposits the funds, only m out of n bridges together can claim the funds from the escrow address and send to the bridge wallet (which is also a multisig wallet). The user can claim the funds after a certain amount of time if the bridges take too long. 

### How this is done is practice? 
Each bridge generates one master key and also a master public key that is shares with the other bridges. During different processes new child public keys are generated e.g. for providing new escrow addresses. During the signing process each replica signs the transaction and stores this signature in its local database. The primary then contacts the other replicas and collects the signatures.

#### Generating the escrow address - Policy, Miniscript and Bitcoin Script
##### Policy: 
`or(and(pk(A),older(1000)),and(thresh(5, pk(B1), pk(B2),pk(B3), pk(B4),pk(B5),pk(B6),pk(B7)),sha256(H)))`

##### Miniscript: 
`andor(pk(A),older(1000),and_v(v:multi(5,B1,B2,B3,B4,B5,B6,B7),sha256(H)))`

##### Bitcoin Script
`<A> OP_CHECKSIG OP_NOTIF
  1 <B1> <B2> <B3> <B4> <B5> <B6> <B7> 7 OP_CHECKMULTISIGVERIFY OP_SIZE <20>
  OP_EQUALVERIFY OP_SHA256 <H> OP_EQUAL
OP_ELSE
  <e803> OP_CHECKSEQUENCEVERIFY
OP_ENDIF`


#### Integration Tests
- Each bridge has its own Master key called pegin-wallet$I.json
- Each bridge knows all master public keys from all bridges called shared-pubkey$i.txt
- Side node: In our integration tests, these are generated during the bridge setup module in the before all function 

##### 1. Run Method of Bridge Consensus Core:  
    - Init pegin Wallet Manager
    - Read and load public keys of all bridges including own (not needed but done for simplicity currently) in order 0 to 6
##### 2. StartSessionController: 
    - Use these public keys and the current pegin wallet index to derive pub keys for all master public keys (including own) 
    - These are used to build the asm script for the escrow address
        - Using same structure as before with simple bridge signature but instead using a multi sig for all bridges 
        - This is the policy or(and(pk(A),older(1000)),and(thresh(5, pk(B1), pk(B2),pk(B3), pk(B4),pk(B5),pk(B6),pk(B7)),sha256(H)))
        - Old version: andor(pk(A),older(1000),and_v(v:pk(B),sha256(H)))
##### 3. StartClaimingProcess: 
    - each replica creates its signature, the primary then collects all the signature and broadcasts the tx