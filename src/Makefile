ansC: PA4.java
	javac -cp ".:../lib/soot-4.6.0-jar-with-dependencies.jar" PA4.java

testC: Testcases/Test1/Test.java
	cd Testcases/Test1 && javac *.java && cd ../../

runTest: PA4.java Testcases/Test1/Test.java
	make testC
	java -cp ".:../lib/soot-4.6.0-jar-with-dependencies.jar" PA4 Test1 > results.txt

clean: *.class Testcases/Test1/*.class
	make cleanSrc
	make cleanTest

cleanSrc: *.class
	rm *.class

cleanTest: Testcases/Test1/*.class
	cd Testcases/Test1 && rm *.class && cd ../../