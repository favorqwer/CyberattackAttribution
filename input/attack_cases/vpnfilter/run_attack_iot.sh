SERVER_ADDR="127.0.0.1"

if [ $# -ge 1 ]
then
  SERVER_ADDR=$1
else
  echo "Usage: $0 server_ip"
  exit
fi

sudo rm /var/run/client_ca.crt
sudo rm /var/run/client.crt
sudo rm /var/run/client.key
sudo rm /var/run/msvf.pid

#sudo ./vpnfilter_stage1 $SERVER_ADDR
sudo python vpnfilter_stage1.py $SERVER_ADDR
