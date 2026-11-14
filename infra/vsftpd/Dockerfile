FROM alpine:3.22

RUN apk add --no-cache vsftpd tini \
 && mkdir -p /srv/ftp /run/vsftpd /var/run/vsftpd/empty

COPY vsftpd.conf /etc/vsftpd/vsftpd.conf
COPY --chmod=0755 entrypoint.sh /usr/local/bin/entrypoint.sh

# 21 = control, 21100-21119 = passive data ports (must match vsftpd.conf).
EXPOSE 21 21100-21119
VOLUME ["/srv/ftp"]

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/entrypoint.sh"]
