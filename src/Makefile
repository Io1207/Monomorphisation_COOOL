ansC: PA4.java
	javac -cp ".:../lib/soot-4.6.0-jar-with-dependencies.jar" PA4.java

testC: Testcases/Test7/Test.java
	cd Testcases/Test7 && javac *.java && cd ../../

runTest: PA4.java Testcases/Test7/Test.java
	make testC
	java -cp ".:../lib/soot-4.6.0-jar-with-dependencies.jar" PA4 Test7 > results.txt

clean:
	make cleanSrc
	make cleanTest

cleanSO:
	cd sootOutput && rm *.jimple

cleanSrc:
	rm *.class

cleanTest:
	cd Testcases/Test7 && rm *.class && cd ../../