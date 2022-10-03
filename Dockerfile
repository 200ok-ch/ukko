FROM 'clojure:openjdk-11-lein-slim-bullseye'
LABEL maintainer="200ok GmbH <info@200ok.ch>"

# install debian packages
RUN apt-get update -y -qq \
        && apt-get -y --no-install-recommends install \
        lftp=4.8.4-2+b1 \
        rsync=3.2.3-4+deb11u1 \
        pandoc=2.9.2.1-1+b1 \
        linkchecker=10.0.1-2 \
        sassc=3.6.1+20201027-1 \
        make=4.3-4.1 \
        zsh=5.8-6+deb11u1 \
        elpa-htmlize=1.55-1 \
        wget2=1.99.1-2.2 \
        && apt-get clean \
        && rm -rf /var/lib/apt/lists/*

# install Emacs 28
# Debian bullseye still ships Emacs 27. Unfortunately, Emacs 27 has a
# bug exporting Org mode content which includes CUSTOM_ID properties.

RUN echo "deb https://emacs.ganneff.de bullseye main" > /etc/apt/sources.list.d/emacs.list
RUN wget2 http://emacs.ganneff.de/apt.key
RUN apt-key add apt.key && rm apt.key

# ganneff.de has an invalid https cert atm. Also, the version used
# will likely change when rebuilding this Docker image in the future.

RUN apt-get -o "Acquire::https::Verify-Peer=false" update -y -qq \
        && apt-get -o "Acquire::https::Verify-Peer=false" -y --no-install-recommends install \
        emacs-snapshot-nox=2:20220705+emacs-28.1.90-157184-g83f059793a-~egd11+1

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
