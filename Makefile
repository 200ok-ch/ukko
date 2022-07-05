.PHONY: ukko
image: Dockerfile
	docker build -t twohundredok/ukko .

.PHONY: ukko
push: image
	docker push twohundredok/ukko

.PHONY: enter
enter:
	docker run -it --rm twohundredok/ukko bash

.PHONY: build
build:
	lein bin
