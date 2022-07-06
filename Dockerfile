FROM 'clojure:openjdk-11-lein-slim-buster'
LABEL maintainer="200ok GmbH <info@200ok.ch>"

# install debian packages
RUN apt-get update -y -qq \
        && apt-get -y --no-install-recommends install \
        emacs-nox=1:26.1+1-3.2+deb10u2 \
        lftp=4.8.4-2 \
        rsync=3.1.3-6 \
        pandoc=2.2.1-3+b2 \
        linkchecker=9.4.0-2 \
        sassc=3.5.0-1 \
        make=4.2.1-1.2 \
        zsh=5.7.1-1+deb10u1 \
        && apt-get clean \
        && rm -rf /var/lib/apt/lists/*

# install clojure dependencies
COPY project.clj .
RUN lein deps

# build the binary
COPY src src
RUN mkdir /root/bin && lein bin
RUN mv /root/bin/ukko /bin/ukko

# copy support files into place
COPY support support
RUN ln -s "$(pwd)/support/emacs" ~/.emacs.d

RUN mkdir /project
WORKDIR /project
CMD ["/bin/bash", "-c", "ukko"]
