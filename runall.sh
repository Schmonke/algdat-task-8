echo "Compiling..."
javac XCompress.java

echo "----------diverse.lyx----------"
java XCompress -c files/diverse.lyx files/diverse.lyx.lzh
java XCompress -d files/diverse.lyx.lzh files/diverse.lyx.lzh.raw
diff files/diverse.lyx files/diverse.lyx.lzh.raw
ls -l files/diverse.lyx files/diverse.lyx.lzh files/diverse.lyx.lzh.raw
echo "-------------------------------"

echo "----------diverse.pdf----------"
java XCompress -c files/diverse.pdf files/diverse.pdf.lzh
java XCompress -d files/diverse.pdf.lzh files/diverse.pdf.lzh.raw
diff files/diverse.pdf files/diverse.pdf.lzh.raw
ls -l files/diverse.pdf files/diverse.pdf.lzh files/diverse.pdf.lzh.raw
echo "-------------------------------"

echo "----------diverse.txt----------"
java XCompress -c files/diverse.txt files/diverse.txt.lzh
java XCompress -d files/diverse.txt.lzh files/diverse.txt.lzh.raw
diff files/diverse.txt files/diverse.txt.lzh.raw
ls -l files/diverse.txt files/diverse.txt.lzh files/diverse.txt.lzh.raw
echo "-------------------------------"

echo "----------opg8-2021.pdf----------"
java XCompress -c files/opg8-2021.pdf files/opg8-2021.pdf.lzh
java XCompress -d files/opg8-2021.pdf.lzh files/opg8-2021.pdf.lzh.raw
diff files/opg8-2021.pdf files/opg8-2021.pdf.lzh.raw
ls -l files/opg8-2021.pdf files/opg8-2021.pdf.lzh files/opg8-2021.pdf.lzh.raw
echo "-------------------------------"