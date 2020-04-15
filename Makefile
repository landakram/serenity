
build-release:
	shadow-cljs release app
	lein uberjar
