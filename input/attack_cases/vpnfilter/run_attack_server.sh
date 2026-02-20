SERVER_ADDR="127.0.0.1"

if [ $# -ge 1 ]
then
  SERVER_ADDR=$1
else
  echo "Usage: $0 server_ip"
  exit
fi

SERVER_FILES="server_files"
mkdir -p $SERVER_FILES/update
cp ip.jpg $SERVER_FILES/update.php
cp malwares/8a20dc9538d639623878a3d3d18d88da8b635ea52e5e2d0c2cce4a8c5a703db1.bin $SERVER_FILES/update/test

cd $SERVER_FILES
exiftool -model="$SERVER_ADDR" update.php

sudo python -m SimpleHTTPServer 80
