ansC: PA4.java
	javac -cp ".:soot-4.6.0-jar-with-dependencies.jar" PA4.java

testC: Testcases/TestX/Test.java
	cd Testcases/TestX && javac *.java && cd ../../

runTest: PA4.java Testcases/TestX/Test.java
	make testC
	java -cp ".:soot-4.6.0-jar-with-dependencies.jar" PA4 TestX > results.txt

clean:
	make cleanSrc
	make cleanTest

cleanSO:
	cd sootOutput && rm *.jimple

cleanSrc:
	rm *.class

cleanTest:
	cd Testcases/TestX && rm *.class && cd ../../