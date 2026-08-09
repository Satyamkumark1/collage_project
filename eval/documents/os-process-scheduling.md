# Process Scheduling in Operating Systems

A process is a program in execution. The operating system's scheduler decides which process gets
the CPU at any given moment, aiming to maximize throughput, minimize waiting time, and keep the
system responsive.

## Process States

A process moves through several states during its lifetime: **New** (being created), **Ready**
(waiting for CPU time), **Running** (currently executing), **Waiting/Blocked** (waiting on I/O or
an event), and **Terminated** (finished execution). The scheduler moves processes between Ready
and Running.

## Scheduling Algorithms

### First-Come, First-Served (FCFS)

FCFS schedules processes in the order they arrive, with no preemption. It is simple but can cause
the "convoy effect," where a long process delays every process behind it.

### Shortest Job First (SJF)

SJF picks the process with the smallest estimated CPU burst time next. It minimizes average
waiting time optimally among non-preemptive algorithms, but requires knowing (or estimating) burst
times in advance, which is often impractical.

### Round Robin (RR)

Round Robin gives each process a fixed time slice (a "quantum"); if a process doesn't finish
within its quantum, it's preempted and moved to the back of the ready queue. A small quantum
improves responsiveness but increases context-switch overhead; a large quantum approaches FCFS
behavior.

### Priority Scheduling

Priority scheduling assigns each process a priority number and runs the highest-priority process
first. It can suffer from **starvation**, where a low-priority process never runs because
higher-priority processes keep arriving. **Aging** — gradually increasing the priority of a
process the longer it waits — is a common fix for starvation.

## Context Switching

A context switch is the act of saving the state (registers, program counter, memory maps) of the
currently running process and loading the state of the next process to run. Context switches are
pure overhead — no useful work happens during one — so schedulers try to minimize how often they
occur while still meeting fairness and responsiveness goals.

## Deadlock

A deadlock occurs when a set of processes are each waiting for a resource held by another process
in the set, so none of them can proceed. Four conditions must all hold simultaneously for deadlock
to occur: mutual exclusion, hold-and-wait, no preemption, and circular wait. Breaking any one of
these four conditions prevents deadlock.
