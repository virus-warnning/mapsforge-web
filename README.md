# mapsforge-web
A tile server powered by Mapsforge and Netty. See [GDTile](http://gdtile.tacosync.com)

以 Mapsforge 和 Netty 搭建的圖磚服務，參見 [鬼島圖磚](http://gdtile.tacosync.com)

# Dockerfile
```Dockerfile
FROM ubuntu:16.04
MAINTAINER Raymond Wu "virus.warnning@gmail.com"

RUN apt-get update
RUN apt-get install -y wget unzip openjdk-8-jdk

# Font you'd like to use
RUN apt-get install -y fonts-wqy-microhei

# Download map file.
RUN mkdir ~/osm-data \
  && cd ~/osm-data \
  && wget https://mirror.ossplanet.net/geomancer/0.1.0/taiwan-taco.map.gz \
  && gunzip taiwan-taco.map.gz

# Download server.
RUN cd ~ \
  && wget https://github.com/virus-warnning/mapsforge-web/archive/master.zip \
  && unzip master.zip \
  && mv mapsforge-web-master/Mapsforge-Web . \
  && rm -rf mapsforge-web-master master.zip \
  && cd Mapsforge-Web \
  && ./gradlew compileJava

EXPOSE 20480
WORKDIR /root/Mapsforge-Web
CMD ["./gradlew", "start"]
```

```
sudo docker build --no-cache -t gdtile:2.x .
sudo docker run --name gdtile2 --ip 172.17.0.2 -t gdtile:2.x
sudo docker start gdtile-2.x
iptables -t nat -A DOCKER -p tcp --dport 80 -j DNAT --to-destination 172.17.0.2:20480
```
