#!/bin/bash

# Stop all containers
docker stop $(docker ps -a -q)

# Remove previous data
rm consensusPrivateKey.pem consensusPublicKey.pem clientPrivateKey.pem clientPublicKey.pem bridge.db


for i in {0..6}; do
  j=$((2 * i))
  rm consensusPrivateKey$i.pem
  rm consensusPublicKey$i.pem
  rm clientPrivateKey$j.pem
  rm clientPublicKey$j.pem
done


for i in {0..6}; do
  rm strata-wallet$i.db
  rm strata-wallet$i.json
done

# Create keys


for i in {0..6}; do
  j=$((2 * i))
  openssl ecparam -name secp256k1 -genkey -noout -out consensusPrivateKey$i.pem
  openssl ec -in consensusPrivateKey$i.pem -pubout -out consensusPublicKey$i.pem
  openssl ecparam -name secp256k1 -genkey -noout -out clientPrivateKey$j.pem
  openssl ec -in clientPrivateKey$j.pem -pubout -out clientPublicKey$j.pem
done

# Start the containers
echo "Starting containers"
docker run --rm -d --add-host host.docker.internal:host-gateway -p 18444:18444 -p 18443:18443 -p 28332:28332 --name bitcoin01 stratalab/bitcoin-zmq:v25-regtest
docker run --rm -d --add-host host.docker.internal:host-gateway -p 18446:18444 -p 18445:18443 -p 28333:28332 --name bitcoin02 stratalab/bitcoin-zmq:v25-regtest


sudo rm -fr node01
sudo rm -fr node02
#rm -fr staking/*
#docker run --rm -i --user root  -p 9085:9085 -p 9084:9084 -p 9091:9091 -v (pwd)/config:/node -v (pwd)/staking:/staking:rw ghcr.io/stratalab/plasma-node:0.0.0-8215-792f55b2 -- --cli true --config  /node/config.conf < config.txt
#sudo chown -R mundacho staking/
mkdir -p node01
mkdir -p node02
chmod 777 node01
chmod 777 node02
# sed -i  -e 's/public/private/' staking/config.yaml
export TIMESTAMP=`date --date="+10 seconds" +%s%N | cut -b1-13`
echo > node01/config.yaml "\
node:
  big-bang:
    staker-count: 2
    local-staker-index: 0
    timestamp: 0
    regtest-enabled: true
    stakes: [10000, 10000]
"
echo > node02/config.yaml "\
node:
  big-bang:
    staker-count: 2
    local-staker-index: 1
    timestamp: 0
    regtest-enabled: true
    stakes: [10000, 10000]
"

export CONTAINER_ID=`docker run --rm -d --name node01 -p 9085:9085 -p 9084:9084 -p 9091:9091 -v $(pwd)/node01:/staking:rw stratalab/plasma-node-tooling:0.0.0-8215-792f55b2b --  --config  /staking/config.yaml --regtest`
export IP_CONTAINER=`docker network inspect bridge | jq  ".[0].Containers.\"$CONTAINER_ID\".IPv4Address" | sed  's:"::g' | sed -n 's:\(.*\)/.*:\1:p'`
echo "IP_CONTAINER: $IP_CONTAINER"
docker run --rm -d --name node02 -e NODE_P2P_KNOWN_PEERS=$IP_CONTAINER:9085 -p 9087:9085 -p 9086:9084 -p 9092:9091 -v $(pwd)/node02:/staking:rw stratalab/plasma-node-tooling:0.0.0-8215-792f55b2b --  --config  /staking/config.yaml --regtest

echo "Waiting for node to start"
# Wait for node to start
sleep 15

# Prepare the environment
echo "Preparing the environment"
shopt -s expand_aliases
alias plasma-cli="cs launch -r https://s01.oss.sonatype.org/content/repositories/staging org.plasmalabs:plasma-cli_2.13:0.0.0+204-3feec2e9-SNAPSHOT -- "
export BTC_USER=bitcoin
export BTC_PASSWORD=password
export STRATA_WALLET_DB=strata-wallet.db
export STRATA_WALLET_JSON=strata-wallet.json
export STRATA_WALLET_MNEMONIC=strata-mnemonic.txt
export STRATA_WALLET_PASSWORD=password
rm -rf $STRATA_WALLET_DB $STRATA_WALLET_JSON $STRATA_WALLET_MNEMONIC 
plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
rm genesisTx.pbuf genesisTxProved.pbuf groupMintingtx.pbuf groupMintingtxProved.pbuf seriesMintingTx.pbuf seriesMintingTxProved.pbuf
plasma-cli wallet init --network private --password password --newwalletdb $STRATA_WALLET_DB --mnemonicfile $STRATA_WALLET_MNEMONIC --output $STRATA_WALLET_JSON
export ADDRESS=$(plasma-cli wallet current-address --walletdb $STRATA_WALLET_DB)
plasma-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 --change-fellowship nofellowship --change-template genesis --change-interaction 1  -t $ADDRESS -w $STRATA_WALLET_PASSWORD -o genesisTx.pbuf -n private -a 1000 -h  127.0.0.1 --port 9084 --keyfile $STRATA_WALLET_JSON --walletdb $STRATA_WALLET_DB --fee 10 --transfer-token lvl
plasma-cli tx prove -i genesisTx.pbuf --walletdb $STRATA_WALLET_DB --keyfile $STRATA_WALLET_JSON -w $STRATA_WALLET_PASSWORD -o genesisTxProved.pbuf
export GROUP_UTXO=$(plasma-cli tx broadcast -i genesisTxProved.pbuf -h 127.0.0.1 --port 9084)
plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
until plasma-cli indexer-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $STRATA_WALLET_DB; do sleep 5; done
echo "label: ToplBTCGroup" > groupPolicy.yaml
echo "registrationUtxo: $GROUP_UTXO#0" >> groupPolicy.yaml
plasma-cli simple-minting create --from-fellowship self --from-template default  -h 127.0.0.1 --port 9084 -n private --keyfile $STRATA_WALLET_JSON -w $STRATA_WALLET_PASSWORD -o groupMintingtx.pbuf -i groupPolicy.yaml  --mint-amount 1 --fee 10 --walletdb $STRATA_WALLET_DB --mint-token group
plasma-cli tx prove -i groupMintingtx.pbuf --walletdb $STRATA_WALLET_DB --keyfile $STRATA_WALLET_JSON -w $STRATA_WALLET_PASSWORD -o groupMintingtxProved.pbuf
export SERIES_UTXO=$(plasma-cli tx broadcast -i groupMintingtxProved.pbuf -h 127.0.0.1 --port 9084)
plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
echo "SERIES_UTXO: $SERIES_UTXO"
until plasma-cli indexer-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $STRATA_WALLET_DB; do sleep 5; done
echo "label: ToplBTCSeries" > seriesPolicy.yaml
echo "registrationUtxo: $SERIES_UTXO#0" >> seriesPolicy.yaml
echo "fungibility: group-and-series" >> seriesPolicy.yaml
echo "quantityDescriptor: liquid" >> seriesPolicy.yaml
plasma-cli simple-minting create --from-fellowship self --from-template default  -h localhost --port 9084 -n private --keyfile $STRATA_WALLET_JSON -w $STRATA_WALLET_PASSWORD -o seriesMintingTx.pbuf -i seriesPolicy.yaml  --mint-amount 1 --fee 10 --walletdb $STRATA_WALLET_DB --mint-token series
plasma-cli tx prove -i seriesMintingTx.pbuf --walletdb $STRATA_WALLET_DB --keyfile $STRATA_WALLET_JSON -w $STRATA_WALLET_PASSWORD -o seriesMintingTxProved.pbuf
export ASSET_UTXO=$(plasma-cli tx broadcast -i seriesMintingTxProved.pbuf -h 127.0.0.1 --port 9084)
plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
echo "ASSET_UTXO: $ASSET_UTXO"
until plasma-cli indexer-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $STRATA_WALLET_DB; do sleep 5; done

for i in {0..6}; do
  cp $STRATA_WALLET_DB strata-wallet$i.db
  cp $STRATA_WALLET_JSON strata-wallet$i.json
done