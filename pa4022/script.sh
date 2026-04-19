#!/bin/bash

# Exit on error
set -e
# rm Testcases/*/*.class
# rm *.class
make ansC

for i in {1..11}; do
    echo "Running for Test$i"

    # Replace Test0 with Test$i in Makefile
    sed -i.bak "s/TestX/Test$i/g" Makefile

    make runTest

    # Move results.txt
    mv results.txt output/Test$i/

    # Restore original Makefile
    mv Makefile.bak Makefile
done