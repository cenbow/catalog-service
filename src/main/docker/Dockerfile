FROM nimbleplatform/nimble-base
MAINTAINER Salzburg Research <nimble-srfg@salzburgresearch.at>
VOLUME /tmp
ARG finalName
ENV JAR '/'$finalName
ARG port
ADD $finalName $JAR
RUN touch $JAR
ENV PORT 8082
EXPOSE $PORT
CMD [${JAR}]
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom", "-jar", $finalName]