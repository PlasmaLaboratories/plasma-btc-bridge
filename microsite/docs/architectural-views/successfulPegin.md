---
sidebar_position: 14
---

# Successful Pegin

## What is a Successful Pegin? 

A successful pegin is the process of securely transferring Bitcoin (BTC) into the Plasma ecosystem. The process follows these key steps:

1. The user requests a pegin session from the bridge
- Bridge responds with an escrow address, session ID, and other necessary information

2. User sends Bitcoin to the provided escrow address (multisig protected)
- This locks the funds in preparation for the cross-chain transfer

3. The minted tokens are sent to the user's plasma redeem address 
- user redeems funds 

3. Bridges collectively claim funds from the escrow address to a claim address
- This step ensures secure locking of the Bitcoin on the Bitcoin network

4. Bridges mint equivalent Plasma BTC on the Plasma side


## Successful Pegin (from Integration tests)
### Requirements
- During the integration tests we are in regtest and control the minting ourselves. On the Plasma side the method is called mintPlasmaBlock, on the Bitcoin side this is generateToAddress which mints new bitcoin blocks and sends rewards to the provided address. 
- Group and series token are now created during the big bang on the node side. Currently these group id and series id are hardcoded during the integration tests. 

### Bridge Setup Module 

1. Creating Master Keys for each bridge
2. Create config files for consensus and public api of each bridge
3. Launch Consensus and public api for each bridge

### Flow
1. Create a Plasma wallet 
2. Add the bridge fellowship to that newly created wallet 
3. Add a secret for that wallet 
4. With that secret and a pubkey (BTC) we can start a session
5. From the session response we get the current session id which we can use to check the current minting status. We also get the escrow address where we can send the funds we want to bridge to as well as min and max height. 
6. These two heights with the created secret we can add a template for our wallet . 
7. The user sends funds (BTC) to the escrow Address (create tx, sign and send)
8. We mint blocks on BTC and Plasma side until the minting status correctly changes to minting. 
9. At this point the bridges claim the funds from the escrow address where the user just send funds to. 
10. Minted funds on the Plasma side are then redeemed by the user. Therefore the user needs lvl tokens which are currently funded with a transaction from the genesis block to the current user Plasma address. 
11. Import the verification keys. 
12. Create, fund with lvls, prove and broadcast a fund redeem tx. (From genesis block to user address)
13. Mint new Plasma blocks until the levels arrived at the address. 
14. Now the user redeems the funds from the redeemAddress. 
15. The user can then wait for the session status to correctly switch to PeginSessionStateSuccessfulPegin and use the minted funds on the Plasma side. 