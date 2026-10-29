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

The tests also build the application JAR and start a separate Java process with
gate classes absent from its application classpath. It loads gates from their
external folder and checks recovery after an instability stop through mouse
interaction, matching the distribution's class loader setup.

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

## Classroom modules and simulation feedback

Bundled half/full adders are named `beispiele/HA` and `beispiele/FA` and stored in
`modules/beispiele/`. Student modules named `HA` or `FA` can be created directly in
`modules/`. Older circuits using the bundled short names still load; an existing
student module takes priority. `circuits/Adder.lsc` explicitly uses the examples.

In normal mode, start a branch at an input and finish it on an existing wire or
junction; Shift-click on a wire also starts a branch. Automatic connections are
created only when a dragged part is dropped with its pins exactly aligned.

Module palette previews do not simulate. If a component receives more than 4096
events in one uninterrupted propagation sequence, the simulation stops and shows
an unstable-circuit message. After this stop, click a switch to prepare its input
for the next start; propagation resumes when you click Simulate again. The
Simulate button reflects the stopped state.
Clocks and delayed components receive a new event budget for each external tick.
