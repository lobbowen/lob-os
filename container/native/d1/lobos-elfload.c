#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
/* LobOS userland ELF loader: map a static ET_EXEC from an unexecutable disk,
   build a proper initial stack (argc/argv/envp/auxv), jump to entry. */
typedef unsigned char u8;
typedef unsigned long ul;
static ul rd(const u8*p,int n){ul v=0;for(int i=0;i<n;i++)v|=(ul)p[i]<<(8*i);return v;}

/* AT_ constants (asm-generic/auxvec.h) */
#define AT_NULL 0
#define AT_IGNORE 1
#define AT_EXECFD 2
#define AT_PHDR 3
#define AT_PHENT 4
#define AT_PHNUM 5
#define AT_PAGESZ 6
#define AT_BASE 7
#define AT_ENTRY 9
#define AT_HWCAP 16
#define AT_HWCAP2 26
#define AT_RANDOM 25

/* put a ul onto stack, decrementing sp */
#define PUSH(sp,v) do { (sp) -= 8; *(ul*)(sp) = (ul)(v); } while(0)

int main(int argc, char** argv){
  if(argc<2){fprintf(stderr,"usage: %s <elf> [args...]\n",argv[0]);return 2;}
  const char* path=argv[1];
  int fd=open(path,O_RDONLY); if(fd<0){perror("open");return 3;}
  struct stat st; fstat(fd,&st);
  u8*f=malloc(st.st_size);
  lseek(fd,0,SEEK_SET);
  if(read(fd,f,st.st_size)!=(ssize_t)st.st_size){fprintf(stderr,"read fail\n");return 9;}
  if(f[0]!=0x7f||f[1]!='E'||f[2]!='L'||f[3]!='F'){fprintf(stderr,"not ELF\n");return 4;}
  ul phoff=rd(f+0x20,8), phentsz=rd(f+0x36,2), phnum=rd(f+0x38,2);
  ul entry=rd(f+0x18,8);

  ul min=~0UL,max=0;
  for(ul i=0;i<phnum;i++){
    u8*p=f+phoff+i*phentsz;
    if(rd(p,4)!=1)continue;
    ul v=rd(p+16,8), ms=rd(p+40,8);
    if(v<min)min=v; if(v+ms>max)max=v+ms;
  }
  min&=~0xfffUL;
  ul maplen=((max+0xfff)&~0xfffUL)-min;
  void*base=mmap((void*)min,maplen,PROT_READ|PROT_WRITE,
                 MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0);

  if(base==MAP_FAILED){write(2,"mmap FAILED\n",12);return 5;}

  for(ul i=0;i<phnum;i++){
    u8*p=f+phoff+i*phentsz;
    if(rd(p,4)!=1)continue;
    ul off=rd(p+8,8), v=rd(p+16,8), fs=rd(p+32,8), ms=rd(p+40,8);
    memcpy((void*)v,f+off,fs);
    ul bss=v+fs; if(ms>fs) memset((void*)bss,0,ms-fs);
    unsigned fl=rd(p+4,4);

    if(fl&1){
      ul pa=v&~0xfffUL, pend=((v+ms)+0xfff)&~0xfffUL;
      mprotect((void*)pa,pend-pa,PROT_READ|PROT_EXEC);
    }
  }

  /* Build initial stack at a high address, top-down. */

  /* Allocate the guest initial stack (64 KiB, anonymous RW). */
  void* stack=mmap((void*)0x70000000UL, 0x10000, PROT_READ|PROT_WRITE,
                   MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0);
  if(stack==MAP_FAILED){write(2,"stack FAILED\n",14);return 6;}

  ul stk_top=0x70008000UL; /* top of the 64 KiB stack region */
  ul sp=stk_top;
  /* Inherit the real auxv from /proc/self/auxv and override only the entries
     that describe the loaded ELF. glibc's static startup uses AT_HWCAP for
     IFUNC selection — hardcoding 0 makes it pick wrong instructions and crash. */
  struct { ul tag, val; } av[80];
  int nav = 0;
  {
    int afd = open("/proc/self/auxv", O_RDONLY);
    struct { ul t, v; } raw;
    if (afd >= 0) {
      while (nav < 70 && read(afd, &raw, sizeof raw) == sizeof raw) {
        if (raw.t == 0) break; /* AT_NULL */
        /* skip entries we must replace */
        if (raw.t == AT_PHDR || raw.t == AT_PHENT || raw.t == AT_PHNUM ||
            raw.t == AT_ENTRY || raw.t == AT_BASE || raw.t == AT_RANDOM) continue;
        av[nav].tag = raw.t; av[nav].val = raw.v; nav++;
      }
      close(afd);
    }
  }
  ul rnd[2]={0x123456789abcdef0UL,0xfedcba9876543210UL};
  const char* envp[]={ "LOBOS_LOADER=1", NULL };
  const char* gargv0=path;
  ul arg_addr[16]; int nargs=argc-2; if(nargs>15)nargs=15; if(nargs<0)nargs=0;
  ul env_addr[1]; int nenv=1;
    sp-=16; memcpy((void*)sp,rnd,16); ul at_random=sp;
  for(int i=0;i<nargs;i++){size_t L=strlen(argv[i+2])+1;sp-=L;memcpy((void*)sp,argv[i+2],L);arg_addr[i]=sp;}
  for(int i=0;i<nenv;i++){size_t L=strlen(envp[i])+1;sp-=L;memcpy((void*)sp,envp[i],L);env_addr[i]=sp;}
  size_t l0=strlen(gargv0)+1; sp-=l0; memcpy((void*)sp,gargv0,l0); ul argv0_addr=sp;
  sp&=~0xfUL;
  /* append the target-specific auxv entries after the inherited ones */
  av[nav].tag=AT_PHDR;  av[nav].val=min+phoff;            nav++;
  av[nav].tag=AT_PHENT; av[nav].val=phentsz;              nav++;
  av[nav].tag=AT_PHNUM; av[nav].val=phnum;                nav++;
  av[nav].tag=AT_ENTRY; av[nav].val=entry;                nav++;
  av[nav].tag=AT_BASE;  av[nav].val=0;                    nav++;
  av[nav].tag=AT_RANDOM;av[nav].val=at_random;            nav++;
  av[nav].tag=AT_NULL;  av[nav].val=0;                    nav++;
  for(int i=nav-1;i>=0;i--){PUSH(sp,av[i].val);PUSH(sp,av[i].tag);}
  PUSH(sp,0);
  for(int i=nenv-1;i>=0;i--)PUSH(sp,env_addr[i]);
  PUSH(sp,0);
  for(int i=nargs-1;i>=0;i--)PUSH(sp,arg_addr[i]);
  PUSH(sp,argv0_addr);
  PUSH(sp,nargs+1);

  /* switch stack and jump: use a naked trampoline to avoid compiler
     prologue touching the old stack after sp is replaced. */
  register ul rsp asm("x0")=sp;
  register ul rent asm("x1")=entry;
  asm volatile(
    "mov sp, x0\n"
    "br x1\n"
    ::"r"(rsp),"r"(rent):"memory");
  __builtin_unreachable();
}
