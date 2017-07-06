# mapsforge-web
A tile server powered by Mapsforge and Netty.

# Dockerfile
```Dockerfile
FROM ubuntu:16.04
MAINTAINER Raymond Wu "virus.warnning@gmail.com"

RUN apt-get update
RUN apt-get install -y wget
RUN apt-get install -y unzip
RUN apt-get install -y openjdk-8-jdk

# Download map file.
RUN mkdir ~/osm-data \
  && cd ~/osm-data \
  && wget https://mirror.ossplanet.net/geomancer/0.1.0/taiwan-taco.map.gz \
  && gunzip taiwan-taco.map.gz \
  && rm -f taiwan-taco.map.gz

# Download server.
RUN cd ~ \
  && wget https://github.com/virus-warnning/mapsforge-web/archive/master.zip \
  && unzip master.zip \
  && mv mapsforge-web-master/Mapsforge-Web . \
  && rm -rf mapsforge-web-master master.zip

EXPOSE 20480
WORKDIR /root/Mapsforge-Web
CMD ["./gradlew", "start"]
```

```
sudo docker build -t gdtile:2.0 .
```
