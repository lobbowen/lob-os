#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

static void step(const char *name, int ok, int code) {
  if (ok)
    printf("%s:OK\n", name);
  else
    printf("%s:FAIL:%s(%d)\n", name, strerror(code), code);
  fflush(stdout);
}

int main(void) {
  setvbuf(stdout, NULL, _IONBF, 0);

  int mfd = open("/dev/ptmx", O_RDWR | O_NOCTTY);
  step("PTMX_OPEN", mfd >= 0, errno);
  if (mfd < 0) {
    printf("SUMMARY:master-open-denied\n");
    return 0;
  }

  int rc = grantpt(mfd);
  step("GRANTPT", rc == 0, errno);

  rc = unlockpt(mfd);
  step("UNLOCKPT", rc == 0, errno);

  char *sn = ptsname(mfd);
  step("PTSNAME", sn != NULL, errno);
  if (sn == NULL) {
    close(mfd);
    printf("SUMMARY:ptsname-denied\n");
    return 0;
  }
  printf("PTSNAME:PATH:%s\n", sn);

  int sfd = open(sn, O_RDWR | O_NOCTTY);
  step("SLAVE_OPEN", sfd >= 0, errno);

  if (sfd >= 0) {
    struct termios t;
    step("TCGETA", tcgetattr(sfd, &t) == 0, errno);
    int room = 0;
    step("TIOCINQ", ioctl(sfd, FIONREAD, &room) == 0, errno);
    close(sfd);
  }
  close(mfd);

  printf("SUMMARY:%s\n", (sfd >= 0) ? "full-pty-usable" : "partial-see-steps");
  return 0;
}
