#!/bin/bash
# deploy-dev.sh
IMAGE_NAME=file-services:latest
TAR_FILE=file-services-image.tar
REMOTE_HOST=192.168.107.20
REMOTE_USER=devuser
# password=kNR83CUSNJr55j

docker save $IMAGE_NAME -o $TAR_FILE
scp $TAR_FILE $REMOTE_USER@$REMOTE_HOST:/home/$REMOTE_USER/
ssh $REMOTE_USER@$REMOTE_HOST "docker load -i /home/$REMOTE_USER/$TAR_FILE && docker rm -f file-services || true && docker run -d --name file-services -p 8003:8003 -p 5432:5432 $IMAGE_NAME"
