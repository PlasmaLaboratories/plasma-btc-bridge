# Plasma-BTC-Bridge

## Overview

This repository contains the source code for the Plasma-BTC-Bridge. The Plasma-BTC-Bridge is a service that allows users
to send Bitcoin to a Plasma account.
The service is composed of two parts a web server and a Bitcoin client.

Web
server:  ![Maven Central Version](https://img.shields.io/maven-central/v/org.plasmalabs/plasma-btc-bridge-consensus_2.13?label=consensus&link=https%3A%2F%2Fs01.oss.sonatype.org%2F%23nexus-search%3Bgav~org.plasmalabs~plasma-btc-bridge-consensus_2.13~~~~kw%2Cversionexpand)

Bitcoin
client: ![Maven Central Version](https://img.shields.io/maven-central/v/org.plasmalabs/plasma-btc-bridge-public-api_2.13?label=publicApi&link=https%3A%2F%2Fs01.oss.sonatype.org%2F%23nexus-search%3Bgav~org.plasmalabs~plasma-btc-bridge-consensus_2.13~~~~kw%2Cversionexpand)

This repository contains

- consensus
- publicApi
- plasmaBtcCli
- integration:  Test the bridge with specific setup of configuration and certain number of bridges
- integration-monitor

### Integration

For developers:

Run integration test in your local requires 2 steps, run a script and then execute a sbt task

This script:

- run 2 bitcoins containers, and 2 plasma nodes container
- clean up {resources, delete test input and output folders}
- creates privates and public keys for PBFT
- create a wallet
- create/prove/broadcast a transaction which transfer 1000 LVLs from the genesis_block to the wallet
- create/prove/broadcast a transaction which transfer 1 Group from genesis_block to the wallet
- create/prove/broadcast a transaction which transfer 1 Series from genesis_block to the wallet

```shell
$ cd integration/scripts/
$ ./prepareAndLauncIntegrationTest.sh
```

The expected outcome should be

```shell
Waiting for node to start
Preparing the environment
Wallet created
Transaction successfully created
Transaction successfully proved
TxoAddress                 : 3ez2cdn9AeXDFMLKjL2RUyoVqDKmGHNypdL8V2EE2Siz#0
LockAddress                : ptetP7jshHVMLZsvEt5QsitpJjG5g5Eoh13ryjzA1LS4zjyuoyoT8uBQtr3h
Type                       : LVL
Value                      : 1000
Transaction successfully created
Transaction successfully proved
GROUP_UTXO: HEL5g6Qv3cMY6ChVmFPVp2HYTBizMncRfxZwvU2LY4zn

TxoAddress                 : 3ez2cdn9AeXDFMLKjL2RUyoVqDKmGHNypdL8V2EE2Siz#0
LockAddress                : ptetP7jshHVMLZsvEt5QsitpJjG5g5Eoh13ryjzA1LS4zjyuoyoT8uBQtr3h
Type                       : LVL
Value                      : 1000

TxoAddress                 : HEL5g6Qv3cMY6ChVmFPVp2HYTBizMncRfxZwvU2LY4zn#0
LockAddress                : ptetP7jshHVMLZsvEt5QsitpJjG5g5Eoh13ryjzA1LS4zjyuoyoT8uBQtr3h
Type                       : Group Constructor
Id                         : 0631c11b499425e93611d85d52e4c71c2ad1cf4d58fb379d6164f486ac6b50d2
Fixed-Series               : a8ef2a52d574520de658a43ceda12465ee7f17e9db68dbf07f1e6614a23efa64
Value                      : 1

Transaction successfully created
Transaction successfully proved
SERIES_UTXO: Tf9ieCGqHUMuDGym4dEHJFaVdDBrKSU7LJoNmkegvFi

TxoAddress                 : 3ez2cdn9AeXDFMLKjL2RUyoVqDKmGHNypdL8V2EE2Siz#0
LockAddress                : ptetP7jshHVMLZsvEt5QsitpJjG5g5Eoh13ryjzA1LS4zjyuoyoT8uBQtr3h
Type                       : LVL
Value                      : 1000

TxoAddress                 : HEL5g6Qv3cMY6ChVmFPVp2HYTBizMncRfxZwvU2LY4zn#0
LockAddress                : ptetP7jshHVMLZsvEt5QsitpJjG5g5Eoh13ryjzA1LS4zjyuoyoT8uBQtr3h
Type                       : Group Constructor
Id                         : 0631c11b499425e93611d85d52e4c71c2ad1cf4d58fb379d6164f486ac6b50d2
Fixed-Series               : a8ef2a52d574520de658a43ceda12465ee7f17e9db68dbf07f1e6614a23efa64
Value                      : 1

TxoAddress                 : Tf9ieCGqHUMuDGym4dEHJFaVdDBrKSU7LJoNmkegvFi#0
LockAddress                : ptetP7jshHVMLZsvEt5QsitpJjG5g5Eoh13ryjzA1LS4zjyuoyoT8uBQtr3h
Type                       : Series Constructor
Id                         : a8ef2a52d574520de658a43ceda12465ee7f17e9db68dbf07f1e6614a23efa64
Fungibility                : group-and-series
Token-Supply               : UNLIMITED
Quant-Descr.               : liquid
Value                      : 1


```

and then

```sbtShell
sbt integration/test
```

**Note**
The script does not shutdown resources, run 'docker stop $(docker ps -a -q)' to stop containers, rerunning it will clean
up and start from scratch.
It is recommended to rerun the script each time the sbt-integration test suite is executed.

### Integration-Monitor

TODO