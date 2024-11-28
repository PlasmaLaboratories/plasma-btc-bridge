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
  rm plasma-wallet$i.db
  rm plasma-wallet$i.json
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
mkdir -p node01
mkdir -p node02
chmod 777 node01
chmod 777 node02
# sed -i  -e 's/public/private/' staking/config.yaml

if [[ "$OSTYPE" == "darwin"* ]]; then
  export TIMESTAMP=$(date -v+10S +%s000)
else 
  export TIMESTAMP=`date --date="+10 seconds" +%s%N | cut -b1-13`
fi

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
    stakes: [10000, 10000]
"

export CONTAINER_ID=`docker run --rm -d --name node01 -p 9085:9085 -p 9084:9084 -p 9091:9091 -v $(pwd)/node01:/staking:rw stratalab/plasma-node-tooling:0.1.4 --  --config  /staking/config.yaml --block-regtest-permission true`
export IP_CONTAINER=`docker network inspect bridge | jq  ".[0].Containers.\"$CONTAINER_ID\".IPv4Address" | sed  's:"::g' | sed -n 's:\(.*\)/.*:\1:p'`
echo "IP_CONTAINER: $IP_CONTAINER"
docker run --rm -d --name node02 -e NODE_P2P_KNOWN_PEERS=$IP_CONTAINER:9085 -p 9087:9085 -p 9086:9084 -p 9092:9091 -v $(pwd)/node02:/staking:rw stratalab/plasma-node-tooling:0.1.4 --  --config  /staking/config.yaml --block-regtest-permission true

echo "Waiting for node to start"
# Wait for node to start
sleep 25

# Prepare the environment
echo "Preparing the environment"
shopt -s expand_aliases
alias plasma-cli="cs launch -r https://s01.oss.sonatype.org/content/repositories/releases org.plasmalabs:plasma-cli_2.13:0.1.3 -- "
export BTC_USER=bitcoin
export BTC_PASSWORD=password
export PLASMA_WALLET_DB=plasma-wallet.db
export PLASMA_WALLET_JSON=plasma-wallet.json
export PLASMA_WALLET_MNEMONIC=plasma-mnemonic.txt
export PLASMA_WALLET_PASSWORD=password
rm -rf $PLASMA_WALLET_DB $PLASMA_WALLET_JSON $PLASMA_WALLET_MNEMONIC 
plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
rm lvlsTransferTx.pbuf lvlsTransferTxProved.pbuf groupTransferTx.pbuf groupTransferTxProved.pbuf seriesTransferTx.pbuf seriesTransferTxProved.pbuf
plasma-cli wallet init --network private --password password --newwalletdb $PLASMA_WALLET_DB --mnemonicfile $PLASMA_WALLET_MNEMONIC --output $PLASMA_WALLET_JSON
export ADDRESS=$(plasma-cli wallet current-address --walletdb $PLASMA_WALLET_DB)

# transfer LVLs from genesisUtxo to the wallet
plasma-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 --change-fellowship nofellowship --change-template genesis --change-interaction 1  -t $ADDRESS -w $PLASMA_WALLET_PASSWORD -o lvlsTransferTx.pbuf -n private -a 1000 -h  127.0.0.1 --port 9084 --keyfile $PLASMA_WALLET_JSON --walletdb $PLASMA_WALLET_DB --fee 10 --transfer-token lvl
plasma-cli tx prove -i lvlsTransferTx.pbuf --walletdb $PLASMA_WALLET_DB --keyfile $PLASMA_WALLET_JSON -w $PLASMA_WALLET_PASSWORD -o lvlsTransferTxProved.pbuf
export LVLS_UTXO=$(plasma-cli tx broadcast -i lvlsTransferTxProved.pbuf -h 127.0.0.1 --port 9084)

plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
until plasma-cli indexer-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $PLASMA_WALLET_DB; do sleep 5; done

# Uncomment if you need to print all Outputs of the genesis block, in this case we do need the groupId, and seriesId to do the next two future transfers, for private network this value is fixed.
# transfer 1 Group from genesisUtxo to wallet, see https://github.com/PlasmaLaboratories/plasma-node/blob/main/blockchain/src/main/scala/org/plasmalabs/blockchain/PrivateTestnet.scala
# Type: Group Constructor
# Id: 0631c11b499425e93611d85d52e4c71c2ad1cf4d58fb379d6164f486ac6b50d2
# Type: Series Constructor
# Id: a8ef2a52d574520de658a43ceda12465ee7f17e9db68dbf07f1e6614a23efa64
#
# plasma-cli node-query block-by-height --height 1 -h 127.0.0.1  --port 9084 -s false

export GROUP_ID=0631c11b499425e93611d85d52e4c71c2ad1cf4d58fb379d6164f486ac6b50d2
plasma-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 --change-fellowship nofellowship --change-template genesis --change-interaction 1  -t $ADDRESS -w $PLASMA_WALLET_PASSWORD -o groupTransferTx.pbuf -n private -a 1 -h  127.0.0.1 --port 9084 --keyfile $PLASMA_WALLET_JSON --walletdb $PLASMA_WALLET_DB --fee 10 --transfer-token group --group-id $GROUP_ID
plasma-cli tx prove -i groupTransferTx.pbuf --walletdb $PLASMA_WALLET_DB --keyfile $PLASMA_WALLET_JSON -w $PLASMA_WALLET_PASSWORD -o groupTransferTxProved.pbuf
export GROUP_UTXO=$(plasma-cli tx broadcast -i groupTransferTxProved.pbuf -h 127.0.0.1 --port 9084)

plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
echo "GROUP_UTXO: $GROUP_UTXO"
until plasma-cli indexer-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $PLASMA_WALLET_DB; do sleep 5; done

export SERIES_ID=a8ef2a52d574520de658a43ceda12465ee7f17e9db68dbf07f1e6614a23efa64
plasma-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 --change-fellowship nofellowship --change-template genesis --change-interaction 1  -t $ADDRESS -w $PLASMA_WALLET_PASSWORD -o seriesTransferTx.pbuf -n private -a 1 -h  127.0.0.1 --port 9084 --keyfile $PLASMA_WALLET_JSON --walletdb $PLASMA_WALLET_DB --fee 10 --transfer-token series --series-id $SERIES_ID
plasma-cli tx prove -i seriesTransferTx.pbuf --walletdb $PLASMA_WALLET_DB --keyfile $PLASMA_WALLET_JSON -w $PLASMA_WALLET_PASSWORD -o seriesTransferTxProved.pbuf

export SERIES_UTXO=$(plasma-cli tx broadcast -i seriesTransferTxProved.pbuf -h 127.0.0.1 --port 9084)
plasma-cli node-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
echo "SERIES_UTXO: $SERIES_UTXO"

until plasma-cli indexer-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $PLASMA_WALLET_DB; do sleep 5; done

for i in {0..6}; do
  cp $PLASMA_WALLET_DB plasma-wallet$i.db
  cp $PLASMA_WALLET_JSON plasma-wallet$i.json
done