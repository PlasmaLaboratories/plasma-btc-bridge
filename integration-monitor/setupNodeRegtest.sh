#!/bin/bash
docker stop $(docker ps -a -q)


docker run -d --rm --add-host host.docker.internal:host-gateway -p 18444:18444 -p 18443:18443 -p 28332:28332 --name=bitcoind ruimarinho/bitcoin-core -chain=regtest -zmqpubrawblock=tcp://0.0.0.0:28332 -rpcuser=test -rpcpassword=test -port=18444 -rpcport=18443 -rpcbind=:18443 -rpcallowip=0.0.0.0/0
docker run -d --rm --name bitcoind2 --add-host host.docker.internal:host-gateway -p 12224:18444 -p 12223:18443 -p 12222:28332 ruimarinho/bitcoin-core -chain=regtest -zmqpubrawblock=tcp://0.0.0.0:28332 -rpcuser=test -rpcpassword=test -port=18444 -rpcport=18443 -rpcbind=:18443 -rpcallowip=0.0.0.0/0
docker run -d --rm --name node -p 9084:9084 docker.io/stratalab/plasma-node:0.0.0-8215-792f55b2
ls -lai

rm -fr node01
rm -fr node02
mkdir -p node01
mkdir -p node02
chmod 777 node01
chmod 777 node02
# export TIMESTAMP=$(date --date="+10 seconds" +%s%N | cut -b1-13)
export TIMESTAMP=$(date -v+10S +%s000)

echo > node01/config.yaml "\
node:
  big-bang:
    staker-count: 2
    local-staker-index: 0
    timestamp: $TIMESTAMP
    regtest-enabled: true
    stakes: [10000, 10000]
"
chmod 777 node01/config.yaml
echo $(pwd)
export CONTAINER_ID=$(docker run -d --rm --name node01 -p 9185:9085 -p 9184:9084 -p 9191:9091 -v $(pwd)/node01:/node-staking:rw docker.io/stratalab/plasma-node:0.1.0 --  --config=/node-staking/config.yaml --regtest)
export IP_CONTAINER=$(docker network inspect bridge | jq  ".[0].Containers.\"$CONTAINER_ID\".IPv4Address" | sed  's:"::g' | sed -n 's:\(.*\)/.*:\1:p')
echo "IP_CONTAINER: $IP_CONTAINER"
echo > node02/config.yaml "\
node:
  big-bang:
    staker-count: 2
    local-staker-index: 1
    timestamp: $TIMESTAMP
    regtest-enabled: true
    stakes: [10000, 10000]
  p2p:
    known-peers: $IP_CONTAINER:9085
"
chmod 777 node02/config.yaml
docker run -d --rm --name node02 -p 9087:9085 -p 9086:9084 -p 9092:9091 -v $(pwd)/node02:/node-staking docker.io/stratalab/plasma-node:0.1.0 --  --config  /node-staking/config.yaml --regtest

