# LogicSim3

## Introduction
LogicSim is a simulation tool for digital circuits.
It has been programmed until 2009 by Andreas Tetzl until version 2.4 and this version is hosted on http://www.tetzl.de/java_logic_simulator_de.html.
In 2020 LogicSim has been reprogrammed by Peter Gabriel (pngabriel@gmail.com) to enhance the program and get rid of old techniques (e.g. applets).

The code is nearly entirely new - a object hierarchy has been designed to make the program more expandable.

The old program used the serialization technique to persist circuits. This has been replaced by a XML-format to describe circuits and modules.
Unfortunately old files written with LogicSim2 cannot be used with LogicSim3.

## Starting LogicSim
If you just want to start LogicSim3:
1. download the current binary distribution (ZIP) from the github-releases-folder.
2. unzip the file
3. double-click LogicSim.jar
4. If this fails, use the bat-file on Windows or start the jar via terminal or console:
   (go to the folder first)
     javaw.exe -jar LogicSim.jar

## Develop and Contribute
1. checkout the project
2. setup your IDE so that "src" will be recognized as source-folder
3. start App.java in logicsim-package
4. build your distro via ant-file build.xml
5. if you fix something, contact me so that we can set you up as contributor

## Create the jar file / the distro
create the jar and complete distribution file via ant-build.
In the ant file the manifest will be added which is necessary for any jar.

## Automated tests

Install a JDK (Java 8 or newer) and Apache Ant, then run from the project folder:

```sh
ant test
```

The dependency-free regression suite runs without a graphical desktop. It covers
file loading and persistence, XML security, circuit editing and undo, gate truth
tables, feedback loops, clock stop/resume, delays, RAM, the program counter, both
adder modules and all supplied example circuits. A failing test produces a nonzero
exit code. Test classes, copied fixtures and generated files stay under `build/`;
the original circuits, modules and user configuration are not changed.

Create the distribution with `ant dist`. Gate classes remain available to tests
after packaging, so `ant dist test` is also supported. GitHub Actions runs the suite
on Java 8 and Java 21 for pushes and pull requests.

## Manual
There is only very sparse documentation about this project - and only in German!
Please experiment with the functions. The manual is in the manual-subfolder in the project 
and will be revised soon.

## Issues
For issue tracking, there is a tracking system on github. It would be nice if you report a bug. 
An email to the author is also ok.
