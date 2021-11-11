FROM 'clojure:openjdk-11-lein-slim-buster'
MAINTAINER 200ok GmbH <info@200ok.ch>
RUN apt-get update -y -qq && apt-get -y install lftp rsync pandoc linkchecker sassc make zsh
COPY project.clj .
RUN lein deps
COPY . .
RUN mkdir /root/bin && lein bin
RUN mv /root/bin/ukko /bin/ukko

RUN mkdir /project
WORKDIR /project
CMD ["/bin/bash" "-c" "ukko"]
