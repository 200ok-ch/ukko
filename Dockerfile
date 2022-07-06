FROM 'clojure:openjdk-11-lein-slim-bullseye'
LABEL maintainer="200ok GmbH <info@200ok.ch>"

# install debian packages
RUN apt-get update -y -qq \
        && apt-get -y --no-install-recommends install \
        emacs-nox=1:27.1+1-3.1 \
        lftp=4.8.4-2+b1 \
        rsync=3.2.3-4+deb11u1 \
        pandoc=2.9.2.1-1+b1 \
        linkchecker=10.0.1-2 \
        sassc=3.6.1+20201027-1 \
        make=4.3-4.1 \
        zsh=5.8-6+deb11u1 \
        elpa-htmlize=1.55-1 \
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
