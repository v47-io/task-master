# TaskMaster for Kotlin

> An unfair, concurrent work scheduler prioritizing newer and high-priority tasks over older, low-priority tasks.

![Maven metadata URL][release-badge-img] ![Maven metadata URL][snapshot-badge-img] ![Build][build-badge]

[release-badge-img]: https://img.shields.io/maven-metadata/v?label=maven%20central&metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fv47%2Ftask-master%2Ftask-master-api%2Fmaven-metadata.xml
[snapshot-badge-img]: https://img.shields.io/maven-metadata/v?label=oss%20snapshots&metadataUrl=https%3A%2F%2Foss.sonatype.org%2Fservice%2Flocal%2Frepositories%2Fsnapshots%2Fcontent%2Fio%2Fv47%2Ftask-master%2Ftask-master-api%2Fmaven-metadata.xml
[build-badge]: https://github.com/v47-io/task-master/workflows/Build/badge.svg?branch=master

## Requirements

- Kotlin 1.4.20

  This is a hard requirement because __TaskMaster__'s API consists mainly of suspending functions
  and uses coroutines to schedule and run the tasks.

- Java >= 1.8

## Download

The library is available in the Central Repository (_soon_)

```groovy
repositories {
  maven { mavenCentral() }
}

dependencies {
  implementation 'io.v47.task-master:task-master-api:1.0.0'
  runtimeOnly    'io.v47.task-master:task-master-impl:1.0.0'
}
```

Or for the latest snapshot version

```groovy
repositories {
  maven {
    name 'oss-sonatype'
    url 'https://oss.sonatype.org/content/repositories/snapshots/'
  }
}
```

## License

TaskMaster is released under the terms of the BSD 3-clause license
