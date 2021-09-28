FROM 'clojure:openjdk-11-lein-slim-buster' AS builder
MAINTAINER 200ok GmbH <info@200ok.ch>
ADD project.clj .
RUN lein deps
ADD . .
RUN mkdir /root/bin && lein bin

FROM openjdk:11
RUN apt-get update -y -qq && apt-get -y install lftp rsync pandoc linkchecker
COPY --from=builder /root/bin/ukko .
RUN mkdir /project
WORKDIR /project
CMD ["/ukko"]
