#!/usr/bin/fish

sudo rm -fr node01
sudo rm -fr node02
mkdir -p node01
mkdir -p node02
chmod 777 node01
chmod 777 node02
# sed -i  -e 's/public/private/' staking/config.yaml
set TIMESTAMP (date --date="+10 seconds" +%s%N | cut -b1-13)
echo > node01/config.yaml "\
node:
  big-bang:
    staker-count: 2
    local-staker-index: 0
    timestamp: $TIMESTAMP
    stakes: [10000, 10000]
"
echo > node02/config.yaml "\
node:
  big-bang:
    staker-count: 2
    local-staker-index: 1
    timestamp: $TIMESTAMP
    stakes: [10000, 10000]
"

set CONTAINER_ID (docker run --rm -d --name node01 -p 9085:9085 -p 9084:9084 -p 9091:9091 -v (pwd)/node01:/staking:rw ghcr.io/plasmalaboratories/plasma-node:0.1.3 --  --config  /staking/config.yaml --block-regtest-permission true)
set IP_CONTAINER (docker network inspect bridge | jq  ".[0].Containers.\"$CONTAINER_ID\".IPv4Address" | sed  's:"::g' | sed -n 's:\(.*\)/.*:\1:p')
echo "IP_CONTAINER: $IP_CONTAINER"
docker run --rm -d --name node02 -e NODE_P2P_KNOWN_PEERS=$IP_CONTAINER:9085 -p 9087:9085 -p 9086:9084 -p 9092:9091 -v (pwd)/node02:/staking:rw ghcr.io/plasmalaboratories/plasma-node:0.1.3 --  --config  /staking/config.yaml --block-regtest-permission true
