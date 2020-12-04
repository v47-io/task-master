# TaskMaster for Kotlin

An unfair, concurrent work scheduler prioritizing newer and high-priority tasks over older, low-priority tasks

## Requirements

- Kotlin 1.4.20
  
  This is a hard requirement because __TaskMaster__ works exclusively with coroutines when executing tasks

## Building it

Building __TaskMaster__ requires at least JDK 1.8 to run the tests. The final library artifacts will support Java 1.6.
