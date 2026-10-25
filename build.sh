#!/bin/bash
SERVER=/root/minecraft
CP=$SERVER/versions/1.21.11/paper-1.21.11.jar:$SERVER/plugins/ProtocolLib.jar:$(find $SERVER/libraries -name "*.jar" | tr '\n' ':')
rm -rf out && mkdir out && javac --release 21 -cp "$CP" -d out src/dev/deephide/DeepHide.java && cp res/* out/ && (cd out && jar cf ../DeepHide.jar .)
