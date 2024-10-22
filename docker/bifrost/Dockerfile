FROM ghcr.io/plasmalaboratories/strata-node:0.0.0-8202-06ff79d9
USER root
RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs
RUN chmod +x cs
RUN mv cs /usr/local/bin
RUN cs setup --yes
## create alias in profile
RUN echo "#!/bin/bash" >> /usr/local/bin/plasma-cli
## run forwarding the parameters
RUN echo 'cs launch -r https://s01.oss.sonatype.org/content/repositories/staging org.plasmalabs:plasma-cli_2.13:0.0.0+204-3feec2e9-SNAPSHOT -- $@' >> /usr/local/bin/plasma-cli
RUN chmod +x /usr/local/bin/plasma-cli
USER 1001:0
RUN plasma-cli wallet init --network private --password password --newwalletdb /tmp/wallet.db --mnemonicfile /tmp/mnemonic.txt  -o /tmp/keyfile.json
# ENTRYPOINT ["/opt/docker/bin/node-node"]
# EXPOSE 9085 9084 9091