FROM alpine:3.22

RUN apk add --no-cache vsftpd tini \
 && mkdir -p /srv/ftp /run/vsftpd /var/run/vsftpd/empty

COPY vsftpd.conf /etc/vsftpd/vsftpd.conf
# Plain COPY + chmod (not COPY --chmod) so the image also builds without BuildKit.
COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod 0755 /usr/local/bin/entrypoint.sh

# 21 = control, 21100-21119 = passive data ports (must match vsftpd.conf).
EXPOSE 21 21100-21119
VOLUME ["/srv/ftp"]

ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/entrypoint.sh"]
