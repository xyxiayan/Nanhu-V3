#!/bin/bash
PRO_PATH=$(pwd)
PRO_NAME=$(basename "$pwd")
ENV_PATH=${PRO_PATH%/*}
[ -n "$XS_ENV" ] && ENV_PATH=$XS_ENV
OUTPUT_FILE="build"
CONFIG="DefaultConfig"
WITH_DRAMSIM3=0
DRAMSIM3_HOME=$DRAMSIM3_HOME
THREADS_USE=1
MEM_USE=2/3
DYN_MEMSIZE=0
SIMULATOR="emu"
#LOG
VERBOSE=1
LOGGED=0
LOG_PATH=""
#EMU_Parameters
CORE_NUMS=$(grep -r "processor" /proc/cpuinfo | wc -l)
EMU_THREADS=$(( CORE_NUMS * THREADS_USE))
EMU_THREADS=16
MinMem=64
FAST_COMPILE=0
COMPILE_FILE="coremark"
USE_VERILATER=0
#SIM PARAMETERS
EMU_ARGS=""
TEST_BIN="microbench"
NO_WAVE=0
EMU_TRACE_BEGIN=00000
EMU_TRACE_END=20000
NUM_CORES=1
CLEAN=1
USE_VCS=0
#VERDI
FILE_LIST="cpu_flist.f"
RC_FILE="$PRO_PATH/tools/verdiRC/l2.rc"
TIMESTAMP=$(date '+%Y-%m-%d@%H:%M')
#VCS PARAMETERS
VCS_OUTPUT="$PRO_PATH/sim"
#VERDI PARAMETERS
VERDI_OUTPUT="$PRO_PATH/$OUTPUT_FILE"
VERDI_FLIST="$VERDI_OUTPUT/$FILE_LIST"


RTL_PATH="$PRO_PATH/rtl"
#function switch
op_openVerdi=0
op_build=0
op_runSim=0
#cmd
CMD_RES=0
CMD_FINISH_MSG=""

GREEN=$'\e[0;32m'
RED=$'\e[0;31m'
BLUE=$'\e[0;34m'
WHITE=$'\e[0;37m'
PURPLE=$'\e[0;35m'
REVERT=$'\33[7m'
NC=$'\e[0m'

################################################################################
#Making prettytable portable as 'prettyt'
#prettytable from https://github.com/jakobwesthoff/prettytable.sh
################################################################################
_prettytable_char_top_left="+"
_prettytable_char_horizontal="─"
_prettytable_char_vertical="|"
_prettytable_char_bottom_left="+"
_prettytable_char_bottom_right="+"
_prettytable_char_top_right="+"
_prettytable_char_vertical_horizontal_left="+"
_prettytable_char_vertical_horizontal_right="+"
_prettytable_char_vertical_horizontal_top="+"
_prettytable_char_vertical_horizontal_bottom="+"
_prettytable_char_vertical_horizontal="+"
# Escape codes

# Default colors
_prettytable_color_blue="0;34"
_prettytable_color_green="0;32"
_prettytable_color_cyan="0;36"
_prettytable_color_red="0;31"
_prettytable_color_purple="0;35"
_prettytable_color_yellow="0;33"
_prettytable_color_gray="1;30"
_prettytable_color_light_blue="1;34"
_prettytable_color_light_green="1;32"
_prettytable_color_light_cyan="1;36"
_prettytable_color_light_red="1;31"
_prettytable_color_light_purple="1;35"
_prettytable_color_light_yellow="1;33"
_prettytable_color_light_gray="0;37"

# Somewhat special colors
_prettytable_color_black="0;30"
_prettytable_color_white="1;37"
_prettytable_color_none="0"

function _prettytable_prettify_lines() {
    cat - | sed -e "s@^@${_prettytable_char_vertical}@;s@\$@	@;s@	@	${_prettytable_char_vertical}@g"
}

function _prettytable_fix_border_lines() {
    cat - | sed -e "1s@ @${_prettytable_char_horizontal}@g;3s@ @${_prettytable_char_horizontal}@g;\$s@ @${_prettytable_char_horizontal}@g"
}

function _prettytable_colorize_lines() {
    local color="$1"
    local range="$2"
    local ansicolor="$(eval "echo \${_prettytable_color_${color}}")"
    cat - | sed -e "${range}s@\\([^${_prettytable_char_vertical}]\\{1,\\}\\)@"$'\E'"[${ansicolor}m\1"$'\E'"[${_prettytable_color_none}m@g"
}

function prettytable() {
    local cols="${1}"
    local color="${2:-none}"
    local input="$(cat -)"
    local header="$(echo -e "${input}"|head -n1)"
    local body="$(echo -e "${input}"|tail -n+2)"
    {
        # Top border
        echo -n "${_prettytable_char_top_left}"
        for i in $(seq 2 ${cols}); do
            echo -ne "\t${_prettytable_char_vertical_horizontal_top}"
        done
        echo -e "\t${_prettytable_char_top_right}"

        echo -e "${header}" | _prettytable_prettify_lines

        # Header/Body delimiter
        echo -n "${_prettytable_char_vertical_horizontal_left}"
        for i in $(seq 2 ${cols}); do
            echo -ne "\t${_prettytable_char_vertical_horizontal}"
        done
        echo -e "\t${_prettytable_char_vertical_horizontal_right}"

        echo -e "${body}" | _prettytable_prettify_lines

        # Bottom border
        echo -n "${_prettytable_char_bottom_left}"
        for i in $(seq 2 ${cols}); do
            echo -ne "\t${_prettytable_char_vertical_horizontal_bottom}"
        done
        echo -e "\t${_prettytable_char_bottom_right}"
    } | column -t -s $'\t' | _prettytable_fix_border_lines | _prettytable_colorize_lines "${color}" "2"
}

################################################################################
#                           Auxiliary functions                              #
################################################################################
print_help ()
{
    {
      printf '%s\t%s\n' "Options: " "surport vcore-env build shell - let things work more effectively "
      printf '%s\t%s\n' "-h|H|help:" "help | please source env.sh first!!!"
      printf '%s\t%s\n' "--simv:" "use vcs simulator"
      printf '%s\t%s\n' "--emu:" "use verilator simulator"
      printf '%s\t%s\n' "--verdi:" "open verdi and load lastest generated fsdb file"
      printf '%s\t%s\n' "-b [SIMULATOR]:" "use [SIMULATOR] to build project generate verilog and simulation bin"
      printf '%s\t%s\n' "-o [FILE]:" "run test in special [FILE] file"
      printf '%s\t%s\n' "-c [FILE]:" "compile speical [FILE] cpp testfile mainly in nexus-am/testst/*"
      printf '%s\t%s\n' "-C:" "clean ./build"
      printf '%s\t%s\n' "-t [FILE]:" "run special [FILE] test"
      printf '%s\t%s\n' "-r:" "one click to run regression test"
      printf '%s\t%s\n' "-d:" "with_dramsim3=true"
    } | prettytable 2 purple
    echo "author:" "qminhao@huanghualiwood@163.com"
    exit
}

run_cmd ()
{
    [[ -n "$3" ]] && cd $3
    res=0
    res1=0
    # Run command
    if [[ $LOGGED -eq 1 ]]; then
        [ ! -d $LOG_PATH ] && mkdir -p $LOG_PATH
        CMD="$1 2>&1 | tee $LOG"
        echo -e "${PURPLE}[runing] $CMD${NC}"
        $1 2>&1 | tee $LOG
        res=$?

        [[ -n "$(grep -o --color -e "failed" -e "Error" -e "ABORT" $LOG)" ]] && res1=1
        [[ -n "$CMD_FINISH_MSG" ]] && [[ -n "$(grep -o --color -e "$CMD_FINISH_MSG" $LOG)" ]] && res1=0
    else
        CMD="$1 >/dev/null 2>&1"
        echo -e "${PURPLE}[runing] $CMD${NC}"
        $1 >/dev/null 2>&1
        res=$?
    fi

    echo "res : $res $res1 $4"
    CMD_RES=0
    CMD_FINISH_MSG=0
    LOGGED=0
    if [[ ! $4 ]] && ( [[ $res -eq 0 ]] || [[ $res1 -eq 0 ]] ); then
        echo -e "${PURPLE}[DONE] $CMD ${NC}"
        [[ $LOGGED -eq 1 ]] && echo -e "[LOGS] $LOG${NC}"
    elif [[ $4 -eq 1  && $res -eq 0 && $res1 -eq 0 ]]; then
       echo -e "${PURPLE}[DONE] $CMD ${NC}"
       [[ $LOGGED -eq 1 ]] && echo -e "[LOGS] $LOG${NC}"
    else    # Command error
        echo -e "${RED}[ERROR] $CMD\n${NC}"
        CMD_RES=1
        [[ $LOGGED -eq 1 ]] && echo -e "[LOGS] $LOG${NC}"
        exit 1
    fi
}

fun_checkInput(){
    if [ -z "$2" ];then
        eval echo "none input,use default:$1 "
    else
        eval $1=$2
    fi
}

fun_checkCore(){
    echo $(($CORE_NUMS*$THREADS_USE))
}

fun_checkEnv(){
    local ENV_NAME=$(echo $ENV_PATH | rev | cut -d/ -f1 | rev)
    local PRO_NAME=$(echo $PRO_PATH | rev | cut -d/ -f1 | rev)

    if [[ $PRO_NAME == "Nanhu-V3" || $PRO_NAME == "XiangShan" ]]; then
        export NOOP_HOME=$(pwd)
    else
        export NOOP_HOME=$(pwd)/generators/Nanhu-v3
    fi
    [[ "$0" != "$BASH_SOURCE" ]] && {
        export NEMU_HOME=${XS_ENV}/NEMU
        export AM_HOME=${XS_ENV}/nexus-am
        export DRAMSIM3_HOME=${XS_ENV}/DRAMsim3
        echo SET NOOP_HOME \(XiangShan RTL Home\): ${NOOP_HOME}
        echo SET NEMU_HOME \(NEMU_HOME\): ${NEMU_HOME}
        echo SET AM_HOME \(AM_HOME\): ${AM_HOME}
        echo SET DRAMSIM3_HOME \(DRAMSIM3_HOME \): ${DRAMSIM3_HOME}
    }
}
fun_checkDiskCapacity(){
  echo ""
}
fun_build(){
    Available=$(( $(grep MemAvailable: /proc/meminfo | awk '{print $2}')/(1024*1024) ))
    MaxMem=$(($Available*$MEM_USE))
    [ $MinMem -lt $MaxMem ] && MinMem=$MaxMem
    SetMem=$(grep -oP 'forkArgs.*\-Xmx[0-9]*G' $PRO_PATH/build.sc |  tr -cd "[0-9]")
    echo -e "${BLUE}[availableMem:${Available}G] [maxMem:${MaxMem}] [setMem:${SetMem}G] "
    [ $SetMem -gt $MaxMem ] && SetMem=$MaxMem
    [ $SetMem -lt $MinMem ] && SetMem=$MinMem
    [ $DYN_MEMSIZE -eq 1 ] && sed -i "s/\-Xmx[0-9]*G/-Xmx${SetMem}G/g" "$PRO_PATH/build.sc" && echo -e "and now reset to ${SetMem}G$NC"
    [ $DYN_MEMSIZE -eq 0 ] && [ $SetMem -ne 64 ] && sed -i "s/\-Xmx[0-9]*G/-Xmx${SetMem}G/g" "$PRO_PATH/build.sc"
    [ ! -d "$PRO_PATH/logs" ] && mkdir -p $PRO_PATH/logs

    LOG_PATH=$PRO_PATH/logs/compile && LOG=$LOG_PATH/$TIMESTAMP.log
    [ $FAST_COMPILE -eq 1 ] && echo "${BLUE}Fast Compile${NC}" && rm $OUTPUT_FILE/emu

    LOGGED=1
    CMD_FINISH_MSG="SimTop.v"

    [ $USE_VCS -eq 1 ] && {
     LOGGED=1
     CMD_FINISH_MSG="Verdi KDB elaboration done and the database successfully generated"
     run_cmd "make simv CONFIG=${1} WITH_DRAMSIM3=$WITH_DRAMSIM3 EMU_TRACE=1 EMU_THREADS=$EMU_THREADS -j$(fun_checkCore)" "compile simulater" $PRO_PATH 1
    }
    [ $USE_VERILATER -eq 1 ] && {
      LOGGED=1
      CMD_FINISH_MSG="SimTop.v"
      run_cmd "make emu CONFIG=${1} WITH_DRAMSIM3=$WITH_DRAMSIM3 EMU_TRACE=1 EMU_THREADS=$EMU_THREADS -j$(fun_checkCore)" "compile simulater" $PRO_PATH 1
    }
#    $USE_VCS && mv $DIFFTEST_DIR/simv $OUTPUT_FILE
    need_rename=0 && [[ $op_openVerdi -eq 1  &&  $op_runSim -eq 1 ]] && need_rename=1
#     $need_rename && [ ! -d build_latest_tmp ] && rm ./build_latest_tmp/
#     $need_rename && mv  ./build ./build_latest_tmp && OUTPUT_FILE=build_latest_tmp && echo "${BLUE}rename build -> build_latest_tmp${NC}"
}
fun_test(){
    cd $PRO_PATH
    [ ! -d "logs" ] && mkdir logs
    echo $TEST_BIN
    TEST_BIN_1=$(find $NOOP_HOME/ready-to-run -maxdepth 1 -name "$TEST_BIN*" -print | awk -F " " '{print $1}' | head -n 1)
    ARG=""

    [ $USE_VCS -eq 1 ] && {
        LOGGED=1
        LOG="$LOG_PATH/$NAME.$(date '+%Y-%m-%d@%H:%M:%S').vcs.log"
        RUN=$(find $VCS_OUTPUT -name "$SIMULATOR*" -type f -executable)
        [[ "$TEST_BIN_1" != "" ]] && TEST_BIN=$TEST_BIN_1 && ARG="$ARG +workload=$TEST_BIN "
        [[ $NO_WAVE -eq 1 ]] && ARG="$ARG +dumpfsdb +dump-wave=fsdb "
        cmd="$RUN $ARG"
        run_cmd "$cmd" "run vcs simulate test" "$PRO_PATH"
    }
    [ $USE_VERILATER -eq 1 ] && {
        echo $EMU_ARGS
        if [[ $EMU_ARGS != "" ]];then
            ARG=$EMU_ARGS
        else
            ARG="-I 20000000 -W 500000"
        fi
        [[ "$TEST_BIN_1" != "" ]] && TEST_BIN=$TEST_BIN_1
        [[ $NO_WAVE -ne 1 ]] && ARG="$ARG --dump-wave -b $EMU_TRACE_BEGIN -e $EMU_TRACE_END --wave-path=$PRO_PATH/$OUTPUT_FILE/$TIMESTAMP.vcd"
        ARG="$ARG --force-dump-result "
        NAME=${TEST_BIN##*/}
        [ "$TEST_BIN_1" == "" ] && TEST_BIN=$(head -n 1 $(find /bigdata/zfw/spec_cpt/take_cpt/$TEST_BIN* -type f  -print))
        [ "$TEST_BIN_1" == "" ] && NAME="$1_$NAME"
        echo $TEST_BIN | awk -F " " '{print $1}'

        LOGGED=1
        LOG_PATH="$PRO_PATH/logs/$(date '+%m-%d')/$OUTPUT_FILE" && LOG="$LOG_PATH/$NAME.$(date '+%Y-%m-%d@%H:%M:%S').t${EMU_THREADS}.emu.log"
        RUN=$(realpath $(find $OUTPUT_FILE -name "$SIMULATOR*" -type f -executable | head -n 1))
        cmd="$RUN -i $TEST_BIN $ARG"
        run_cmd "$cmd" "run emu simulate test" "$PRO_PATH" 1
    }

    [[ $CMD_RES -eq 0 ]] && grep -q '\[PERF \]' $LOG && {
        sed -n '/\[PERF \].*/p' $LOG > temp.log
        sed '/\[PERF \].*/d' $LOG >> temp.log
        mv temp.log $LOG
    }
}
fun_getTimeStamp(){
  LAST_MODIFY_TIMESTAMP=$(stat -c %Y "$1")
  TIMESTAMP=$(date '+%s' -d @$LAST_MODIFY_TIMESTAMP)
}
fun_findlatestVCDFile(){
  echo -e "${PURPLE}------------------------------------------------\n*${FUNCNAME}*${NC}"
  cd $1
  [[ -n "$(find . -type f -name "*.fsdb" -a ! -name "stdin.fsdb")" ]] && LATEST_FSDB_FILE=$(ls -lt *.fsdb | head -n1 | awk '{print $9}') && fun_getTimeStamp "$LATEST_FSDB_FILE" && fsdb_timeStamp=$TIMESTAMP
  [[ -n "$(find . -type f -name "*.vcd")" ]] && LATEST_VCD_FILE=$(ls -lt *.vcd | head -n1 | awk '{print $9}') && fun_getTimeStamp "$LATEST_VCD_FILE" && vcd_timeStamp=$TIMESTAMP

  [[ $fsdb_timeStamp -lt $vcd_timeStamp ]] && LATEST_FSDB_FILE=""
}
fun_checkRC(){
  echo -e "${PURPLE}------------------------------------------------\n*${FUNCNAME}*${NC}"
     if [ -f ${RC_FILE} ]; then
       path=$(grep "activeDirFile" ${RC_FILE} | head -n 1 | awk '{print $3}' | sed 's/\"//g')

       if [[ $path != $LATEST_FSDB_FILE ]]; then
         replace=$(echo ${LATEST_FSDB_FILE} | sed 's/\//\\\//g')
         path=$(echo ${path} | sed 's/\//\\\//g')
         echo " ${RED}RC Path need update${NC} "
         sed -i -e "s/${path}/${replace}/gp" $RC_FILE
       fi
     fi
}
fun_openVerdi(){
    echo -e "${PURPLE}------------------------------------------------\n*${FUNCNAME}*${NC}"
    rubbish=$(find $PRO_PATH  -maxdepth 5 -name "*verdi*" -o -name "*novas*" | grep $OUTPUT_FILE | xargs)
    [[ ! -f ${RC_FILE} ]] && RC_FILE=$(find $PRO_PATH/tools/verdiRC -type f -name "*.rc" | head -n1)
    [[ ! -f ${RC_FILE} ]] && RC_FILE=""
    [[ ! $rubbish == "" ]] && echo "rm ${GREEN}$rubbish${NC}" && rm -rf $rubbish

    [ $USE_VERILATER -eq 1 ] &&{
        if [ ! -d "$VERDI_OUTPUT" ];then
            echo "need build first"
            exit 1
        fi
        fun_findlatestVCDFile "$VERDI_OUTPUT"
        [[ -z "$LATEST_FSDB_FILE" ]] && {
            run_cmd "vcd2fsdb $LATEST_VCD_FILE" "vcd2fsdb" "$VERDI_OUTPUT"
            sleep 1 
            LATEST_FSDB_FILE=$(find $VERDI_OUTPUT -type f -name "$LATEST_VCD_FILE.fsdb")
        }
        [[ -z "$LATEST_FSDB_FILE" ]] && echo "${RED}error" && exit 1

        [ "$(sed -n "/[\w,\/]*\/$OUTPUT_FILE\/*/p" $VERDI_FLIST | xargs)" == "" ] && {
            echo "modify ${FILE_LIST} path"
            sed -i "s/\/build/\/$OUTPUT_FILE/g" "$VERDI_FLIST"
        }
        cd $PRO_PATH/$OUTPUT_FILE
        DIFFTEST_DIR="$NOOP_HOME/difftest"
        [[ ! -f $DIFFTEST_DIR/src/test/vsrc/vcs/TOP.v ]] && {
            echo -e "\
            module TOP();\n\
                SimTop SimTop();\n\
            endmodule" > $DIFFTEST_DIR/src/test/vsrc/vcs/TOP.v
        }
        [[ $(find ./ -name $FILE_LIST | xargs sed -n '/SimTop/p') == "" ]] && {
            SIMTOP_FILE=$(find ./ -type f \( -name "SimTop*.v" -o -name "SimTop*.sv" \) -print -quit)
            [[ ! -z "$SIMTOP_FILE" ]] && realpath "SIMTOP_FILE" >> $FILE_LIST
        }
        [[ $(find ./ -name $FILE_LIST | xargs sed -n '/TOP/p') == "" ]] && realpath $DIFFTEST_DIR/src/test/vsrc/vcs/TOP.v >> $FILE_LIST
        [[ $(find ./ -name $FILE_LIST | xargs sed -n '/top/p') == "" ]] && realpath $DIFFTEST_DIR/src/test/vsrc/vcs/top.v >> $FILE_LIST

        cmd="verdi -rcFile ${HOME}/.novas.rc -f $VERDI_FLIST -ssf $LATEST_FSDB_FILE -sswr $RC_FILE"
        run_cmd "$cmd" "open verdi" "$VERDI_OUTPUT"
    }

    [ $USE_VCS -eq 1 ] && {
        cd $PRO_PATH/$OUTPUT_FILE

#        [ "$(sed -n "/[\w,\/]*\/$OUTPUT_FILE\/*/p" $VERDI_FLIST | xargs)" == "" ] && {
#            echo "modify ${FILE_LIST} path"
#            sed -i "s/\/build/\/$OUTPUT_FILE/g" "$VERDI_FLIST"
#        }

        [ $USE_VCS -eq 1 ] && HAS_FSDB=$(find $PRO_PATH -type f -name "*.fsdb" -a ! -name "stdin.fsdb")
        [ -n "$HAS_FSDB" ] && LATEST_FSDB_FILE=$(ls -lt $PRO_PATH/*.fsdb | head -n1 | awk '{print $9}') && fun_getTimeStamp "$LATEST_FSDB_FILE" && fsdb_timeStamp=$TIMESTAMP
        [ $USE_VCS -eq 1 ] && cmd="verdi  -rcFile ${HOME}/.novas.rc -simflow -simBin ./simv -ssf $LATEST_FSDB_FILE -sswr $RC_FILE"
        [ $USE_VCS -eq 1 ] && VERDI_OUTPUT=$PRO_PATH/sim/rtl/comp
        if [[ -z $op_tlTest ]];then
        [[ ! -f $DIFFTEST_DIR/src/test/vsrc/vcs/TOP.v ]] && {
            echo -e "\
            module TOP();\n\
                SimTop SimTop();\n\
            endmodule" > $DIFFTEST_DIR/src/test/vsrc/vcs/TOP.v
        }
        [[ $(find ./ -name $FILE_LIST | xargs sed -n '/SimTop/p') == "" ]] && {
            SIMTOP_FILE=$(find ./ -type f \( -name "SimTop*.v" -o -name "SimTop*.sv" \) -print -quit)
            [[ ! -z "$SIMTOP_FILE" ]] && realpath "SIMTOP_FILE" >> $FILE_LIST
        }
        [[ $(find ./ -name $FILE_LIST | xargs sed -n '/TOP/p') == "" ]] && realpath $DIFFTEST_DIR/src/test/vsrc/vcs/TOP.v >> $FILE_LIST
        [[ $(find ./ -name $FILE_LIST | xargs sed -n '/top/p') == "" ]] && realpath $DIFFTEST_DIR/src/test/vsrc/vcs/top.v >> $FILE_LIST
        fi

        run_cmd "$cmd" "open verdi" "$VERDI_OUTPUT"
    }
}

fun_checkDutImg(){
  [ ! -d $NEMU_HOME ] && cd $NEMU_HOME
}
fun_tlTest(){
  echo -e "${PURPLE}------------------------------------------------\n*${FUNCNAME}*${NC}"
  [[ ! -d $TLTEST_PATH ]] && echo "set TLTEST_PATH first " && exit
  [[ $op_openVerdi -eq 1 ]] && DUMP_WAVE="-b 10000 -e 20000 -f"
  run_cmd "cmake .. -DDUT_DIR=${NOOP_HOME}/rtl -DPF=Prefetch -DTRACE=${TRACE}" "build tlTest" "$TLTEST_PATH/build" 1
  run_cmd "make" "build tlTest" "$TLTEST_PATH/build" 1
  cmd="${TLTEST_PATH}/build/tlc_test -c 100000 ${DUMP_WAVE}"
  run_cmd "$cmd" "tl-test simulating " "$TLTEST_PATH/build"
  VERDI_OUTPUT="$TLTEST_PATH/build"
  VERDI_FLIST="$RTL_PATH/huangli.f"
}
#-------------------main menu-------------------#
fun_main(){
    set -- $(getopt -o a::b::t::o:c:s:dhC -l simv,emu,verdi,tltest,help -- "$@")

    args=()
    INNER_ARGS=""
    TRACED=0
    for arg in "$@"; do
        arg=${arg//\'/}
        # echo "=== ${arg: 0:1} ${arg: -1} $TRACED"
        if [[ "${arg:0:1}" = "|" ]] && [[ $TRACED -eq 0 ]]; then
            INNER_ARGS=${arg//\|/}
            # echo "start $INNER_ARGS"
            TRACED=1
        elif [[ "${arg: -1}" = "|" ]] && [[ $TRACED -eq 1 ]]; then
            INNER_ARGS=$INNER_ARGS#${arg//\|/}
            # echo "end $INNER_ARGS"
            args+=("${INNER_ARGS[@]}")
            TRACED=0
        elif [[ "$TRACED" -eq 1 ]]; then
            INNER_ARGS=$INNER_ARGS#$arg
        elif [ -n "$arg" ]; then
            args+=("$arg")
            echo "$arg"
        fi
    done

    set -- "${args[@]}"
    echo "${args[@]}"
    while [ -n "$1" ];do
        tmp=${2#*\'}
        ARG=${tmp%\'*}
        [[ $ARG == "-"* ]] && ARG=""
        
        case "$1" in
            -h|H|--help) print_help;;
            --simv) SIMULATOR="simv" && USE_VCS=1;;
            --emu) SIMULATOR="emu" && USE_VERILATER=1;;
            --tltest) op_tlTest=1;;
            --verdi) op_openVerdi=1;;
            -C)CLEAN=true; run_cmd "make clean" "make clean" "$PRO_PATH";;
            -a)[[ -n $ARG ]]&&EMU_ARGS="$ARG"&&echo $EMU_ARGS;;
            -b)[[ -n $ARG ]]&&CONFIG=$ARG;op_build=1;;
            -d)WITH_DRAMSIM3=1;;
            -f)FAST_COMPILE=1;;
            -c)COMPILE_FILE=$ARG run_cmd "make " "compile cpp testfile";;
            -o)OUTPUT_FILE=$ARG;;
            -t){
                IFS='#' read -ra INNER_ARGS_ARRAY <<< "$ARG"
                set -- $(getopt -o s:C:I:W:i:b::e:: -l no-wave,dump-wave,no-diff -- "${INNER_ARGS_ARRAY[@]}")
                while [ -n "$1" ];do
                    tmp=${2#*\'}
                    ARG=${tmp%\'*}
                    case "$1" in
                        --no-diff) EMU_ARGS="$EMU_ARGS --no-diff ";echo -ne "${PURPLE}enable dump wave${NC}";;
                        -i)TEST_BIN=$ARG && echo -ne "${PURPLE}enable dump wave${NC}";;
                        --no-wave)NO_WAVE=1;echo -ne "${PURPLE}disable dump wave${NC}";;
                        -b)EMU_TRACE_BEGIN=$ARG; echo -ne "${PURPLE} wave range [$ARG ${NC},";;
                        -e)EMU_TRACE_END=$ARG; echo -ne "${PURPLE}$ARG] ${NC}";;
                        ?)break;;
                    esac
                    shift 1
                done
                echo
                op_runSim=1
                [[ -n $ARG ]]&&TEST_BIN=$ARG;op_runSim=1
            };;

            --)shift 1;break;;
            ?)echo "$2 miss praramter";;
        esac
        shift 1
        # [[ -n "$ARG" ]] && shift 1
    done
}

fun_main "$@"
fun_checkEnv
[[ $CMD_RES -eq 0 ]] && [[ $op_tlTest -eq 1 ]] && fun_tlTest
[[ $CMD_RES -eq 0 ]] && [[ $op_build -eq 1 ]] && fun_build $CONFIG
[[ $CMD_RES -eq 0 ]] && [[ $op_runSim -eq 1 ]] && fun_test
[[ $CMD_RES -eq 0 ]] && [[ $op_openVerdi -eq 1 ]]  && fun_checkRC && fun_openVerdi