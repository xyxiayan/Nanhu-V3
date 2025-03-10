# XiangShan

XiangShan (香山) is an open-source high-performance RISC-V processor project.

中文说明[在此](readme.zh-cn.md)。

Copyright 2020-2022 by Institute of Computing Technology, Chinese Academy of Sciences.

Copyright 2020-2022 by Peng Cheng Laboratory.

## Docs and slides

[XiangShan-doc](https://github.com/OpenXiangShan/XiangShan-doc) is our official documentation repository. It contains design spec., technical slides, tutorial and more.

* Micro-architecture documentation of XiangShan has been published. Please check out https://xiangshan-doc.readthedocs.io

## Follow us

Wechat/微信：香山开源处理器

<div align=left><img width="340" height="117" src="images/wechat.png"/></div>

Zhihu/知乎：[香山开源处理器](https://www.zhihu.com/people/openxiangshan)

Weibo/微博：[香山开源处理器](https://weibo.com/u/7706264932)

You can contact us through [our mail list](mailto:xiangshan-all@ict.ac.cn). All mails from this list will be archived to [here](https://www.mail-archive.com/xiangshan-all@ict.ac.cn/).

## Architecture

The first stable micro-architecture of XiangShan is called Yanqihu (雁栖湖) on this [branch](https://github.com/OpenXiangShan/XiangShan/tree/yanqihu), which has been developed since June 2020. The current version of XiangShan, also known as Nanhu (南湖), is still under development on the main branch. Present version of Nanhu is V3.

The micro-architecture overview of Nanhu-V3 (南湖V3) is shown below.

![xs-arch-nanhu](images/xs-arch-neo-nanhu.svg)



## Sub-directories Overview

Some of the key directories are shown below.

```
.
├── src
│   └── main/scala         # design files
│       ├── device         # virtual device for simulation
│       ├── system         # SoC wrapper
│       ├── top            # top module
│       ├── utils          # utilization code
│       ├── xiangshan      # main design code
│       └── xstransforms   # some useful firrtl transforms
├── scripts                # scripts for agile development
├── fudian                 # floating unit submodule of XiangShan
├── huancun                # L2/L3 cache submodule of XiangShan
├── difftest               # difftest co-simulation framework
└── ready-to-run           # pre-built simulation images
```

## IDE Support

### bsp
```
make bsp
```

### IDEA
```
make idea
```


## Generate Verilog

* Run `make verilog` to generate verilog code. The output file is `build/XSTop.v`.
* Refer to `Makefile` for more information.



## Run Programs by Simulation

### Prepare environment

* Set environment variable `NEMU_HOME` to the **absolute path** of the [NEMU project](https://github.com/OpenXiangShan/NEMU).
* Set environment variable `NOOP_HOME` to the **absolute path** of the XiangShan project.
* Set environment variable `AM_HOME` to the **absolute path** of the [AM project](https://github.com/OpenXiangShan/nexus-am).
* Install `mill`. Refer to [the Manual section in this guide](https://com-lihaoyi.github.io/mill/mill/Intro_to_Mill.html#_installation).
* Clone this project and run `make init` to initialize submodules.

### Run with simulator

* Install [Verilator](https://verilator.org/guide/latest/), the open-source Verilog simulator.
* Run `make emu` to build the C++ simulator `./build/emu` with Verilator.
* Refer to `./build/emu --help` for run-time arguments of the simulator.
* Refer to `Makefile` and `verilator.mk` for more information.

Example:

```bash
make emu CONFIG=MinimalConfig EMU_THREADS=2 -j10
./build/emu -b 0 -e 0 -i ./ready-to-run/coremark-2-iteration.bin --diff ./ready-to-run/riscv64-nemu-interpreter-so
```

## Troubleshooting Guide

[Troubleshooting Guide](https://github.com/OpenXiangShan/XiangShan/wiki/Troubleshooting-Guide)

## Acknowledgement

In the development of XiangShan, some sub-modules from the open-source community are employed. All relevant usage is listed below.

| Sub-module         | Source                                                       | Detail                                                       |
| ------------------ | ------------------------------------------------------------ | ------------------------------------------------------------ |
| L2 Cache/LLC       | [Sifive block-inclusivecache](https://github.com/ucb-bar/block-inclusivecache-sifive) | Our new L2/L3 design are inspired by Sifive's `block-inclusivecache`. |
| Diplomacy/TileLink | [Rocket-chip](https://github.com/chipsalliance/rocket-chip)  | We reused the Diplomacy framework and TileLink utility that exist in rocket-chip to negotiate bus. |

We are grateful for the support of the open-source community and encourage other open-source projects to reuse our code within the scope of the [license](LICENSE).

