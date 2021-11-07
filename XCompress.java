import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Stack;

class BitSegment {
    private final int size;
    private final long bits;

    public BitSegment(int size, long bits) {
        this.size = size;
        this.bits = bits;
    }

    public int getSize() {
        return size;
    }

    public long getBits() {
        return bits;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(size);
        for (int i = size - 1; i >= 0; i--) {
            builder.append((bits & (1 << i)) >> i);
        }
        return builder.toString();
    }
}

class BitBuffer {
    private int size = 0;
    private long bits = 0;

    public void add(BitSegment bitSegment) {
        long newBits = bitSegment.getBits();
        int newBitsSize = bitSegment.getSize();
        size += newBitsSize;
        bits = (bits << newBitsSize) | newBits;
    }

    public int read() {
        if (size < 8) {
            return -1;
        }
        int shift = size - Byte.SIZE;
        int b = (int) ((bits & (0xFF << shift)) >> shift);
        size -= Byte.SIZE;
        return b;
    }

    public int readFinal() {
        if (size == 0) {
            return -1;
        }
        int b = (int) (bits & ((1 << size) - 1)) << (Byte.SIZE - size);
        return b;
    }
}

class VariableWidthEncoder {
    // First bit of every byte decides whether the next byte is a continuation byte
    // (up to 5 continuous bytes).

    private static final int[] BYTE_ALIGN_PAD = { 0, 0, 0, 6 };
    private static final int[] VALUE_WIDTHS = { Byte.SIZE - 2, Short.SIZE - 2, 24 - 2, Integer.SIZE - 2, Integer.SIZE };

    public static int decode(DataInput dataInput) throws IOException {
        int firstByte = dataInput.readByte() & 0xFF;
        int offset = Long.BYTES;
        int widthIndex = firstByte >> 6;
        int width = VALUE_WIDTHS[widthIndex];
        int bytesLeft = ((width + 2) / 8) - 1;
        offset = (width + 2) / 8;

        long data = (firstByte & 0b00111111) << (--offset * 8);
        for (int i = 0; i < bytesLeft; i++) {
            data |= (dataInput.readByte() & 0xFF) << (--offset * 8);
        }
        return (int) (data >> BYTE_ALIGN_PAD[widthIndex]);
    }

    private static void encodeWidth(int value, int widthIndex, DataOutput dataOutput) throws IOException {
        int valueBitWidth = VALUE_WIDTHS[widthIndex];
        int actualWidth = valueBitWidth + 2;
        long data = ((long) widthIndex << valueBitWidth) | value & 0xFFFFFFFFL;
        data <<= BYTE_ALIGN_PAD[widthIndex];
        int totalWidth = actualWidth + BYTE_ALIGN_PAD[widthIndex];
        System.out.printf("ACTUALWIDTH: %d%n", actualWidth);
        for (int i = totalWidth - 8; i >= 0; i -= 8) {
            System.out.printf("(%d)", i);
            System.out.printf("%X ", (int) ((data >> i) & 0xFF));
            dataOutput.write((int) ((data >> i) & 0xFF));
        }
        System.out.println();
    }

    public static void encode(int value, DataOutput dataOutput) throws IOException {
        int widthIndex = -1;
        for (int i = 0; i < VALUE_WIDTHS.length; i++) {
            long maxValue = (1L << VALUE_WIDTHS[i]) - 1L;
            if (value <= maxValue) {
                widthIndex = i;
                break;
            }
        }
        encodeWidth(value, widthIndex, dataOutput);
    }
}

class Constants {
    public static final int BYTE_MAX_POSSIBILITIES = 1 << Byte.SIZE;
}

class HuffmanFrequencies {
    int[] frequencies = new int[Constants.BYTE_MAX_POSSIBILITIES];

    private void setFrequency(byte c, int frequency) {
        frequencies[c & 0xFF] = frequency;
    }

    public void increment(byte c) {
        frequencies[c & 0xFF]++;
    }

    public int[] getFrequencies() {
        return frequencies.clone();
    }

    public void serialize(OutputStream outputStream) throws IOException {
        int nonZeroFrequencies = (int) Arrays.stream(frequencies).filter(x -> x != 0).count();
        DataOutputStream dataOutputStream = new DataOutputStream(outputStream);
        dataOutputStream.writeByte(nonZeroFrequencies);
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] != 0) {
                dataOutputStream.writeByte(i);
                dataOutputStream.writeInt(frequencies[i]);
            }
        }
        dataOutputStream.flush();
    }

    public static HuffmanFrequencies deserialize(InputStream inputStream) throws IOException {
        HuffmanFrequencies instance = new HuffmanFrequencies();
        DataInputStream dataInputStream = new DataInputStream(inputStream);
        int frequencyCount = dataInputStream.readByte() & 0xFF;
        for (int i = 0; i < frequencyCount; i++) {
            byte c = dataInputStream.readByte();
            int frequency = dataInputStream.readInt();
            instance.setFrequency(c, frequency);
        }
        return instance;
    }
}

class HuffmanTree {
    private final BitSegment[] encodeLut;
    private final Node root;
    private Node decodeNode;

    public HuffmanTree(HuffmanFrequencies frequencies) {
        root = generateTree(frequencies.getFrequencies());
        decodeNode = root;
        encodeLut = createEncodeLut(root);
    }

    public BitSegment encode(byte c) {
        return encodeLut[c & 0xFF];
    }

    public int decode(byte c, byte[] out) {
        int bytes = 0;
        for (int i = Byte.SIZE - 1; i >= 0; i--) {
            int bit = (c >> i) & 1;
            decodeNode = decodeNode.getChild(bit);
            if (decodeNode.isLeaf()) {
                out[bytes++] = decodeNode.getValue();
                decodeNode = root;
            }
        }
        return bytes;
    }

    private BitSegment[] createEncodeLut(Node root) {
        class Entry {
            public final Node node;
            public final BitSegment bits;

            public Entry(Node node, BitSegment bits) {
                this.node = node;
                this.bits = bits;
            }
        }
        BitSegment[] encodeLut = new BitSegment[Constants.BYTE_MAX_POSSIBILITIES];
        if (root == null) {
            return encodeLut;
        }

        Stack<Entry> stack = new Stack<>();

        stack.push(new Entry(root, new BitSegment(0, 0)));

        while (stack.size() > 0) {
            Entry entry = stack.pop();
            Node node = entry.node;
            if (node.isLeaf()) {
                encodeLut[entry.node.value & 0xFF] = entry.bits;
                continue;
            }

            int newSize = entry.bits.getSize() + 1;
            long oldBits = entry.bits.getBits();

            Node left = node.getChild(0);
            Node right = node.getChild(1);

            BitSegment leftBits = new BitSegment(newSize, oldBits << 1);
            BitSegment rightBits = new BitSegment(newSize, (oldBits << 1) | 1);

            stack.push(new Entry(right, rightBits));
            stack.push(new Entry(left, leftBits));
        }

        return encodeLut;
    }

    private Node generateTree(int[] frequencies) {
        Comparator<Node> nodeComparator = Comparator.comparing(Node::getFrequency);
        PriorityQueue<Node> nodes = new PriorityQueue<>(frequencies.length, nodeComparator);

        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] != 0) {
                nodes.add(new Node(frequencies[i], (byte) i));
            }
        }

        while (nodes.size() > 1) {
            Node right = nodes.poll();
            Node left = nodes.poll();
            Node parent = new Node(left, right);
            nodes.add(parent);
        }

        return nodes.poll();
    }

    static class Node {
        public Node(Node left, Node right) {
            this.left = left;
            this.right = right;
            if (left != null)
                this.frequency += left.frequency;
            if (right != null)
                this.frequency += right.frequency;
        }

        public Node(int frequency, byte value) {
            this.frequency = frequency;
            this.value = value;
        }

        private Node left;
        private Node right;
        private int frequency;
        private byte value;

        public boolean isLeaf() {
            return left == null && right == null;
        }

        public byte getValue() {
            return value;
        }

        public int getFrequency() {
            return frequency;
        }

        public Node getChild(int pos) {
            switch (pos) {
            case 0:
                return left;
            case 1:
                return right;
            default:
                throw new IllegalArgumentException("pos must be 0 or 1");
            }
        }
    }
}

class HuffmanAlgorithm {
    private static final int BLOCK_SIZE = 16777220; // 16 MiB

    private final InputStream inputStream;
    private final OutputStream outputStream;

    public HuffmanAlgorithm(InputStream inputStream, OutputStream outputStream) {
        this.inputStream = new BufferedInputStream(inputStream);
        this.outputStream = new BufferedOutputStream(outputStream);
    }

    private void compressBlock(byte[] block, int length) throws IOException {
        System.out.println("Constructing frequency table...");
        HuffmanFrequencies frequencies = new HuffmanFrequencies();
        for (int i = 0; i < length; i++) {
            frequencies.increment(block[i]);
        }

        frequencies.serialize(outputStream);
        new DataOutputStream(outputStream).writeInt(length);

        System.out.printf("Compressing block (%d B)...%n", length);
        BitBuffer bitBuffer = new BitBuffer();
        HuffmanTree tree = new HuffmanTree(frequencies);

        for (int i = 0; i < length; i++) {
            BitSegment bitSegment = tree.encode(block[i]);
            bitBuffer.add(bitSegment);
            int b;
            while ((b = bitBuffer.read()) != -1) {
                outputStream.write(b);
            }
        }
        int b = bitBuffer.readFinal();
        if (b != -1) {
            outputStream.write(b);
        }
    }

    public void compress() throws IOException {
        byte[] block = new byte[BLOCK_SIZE];
        int length;
        while ((length = inputStream.read(block)) != -1) {
            compressBlock(block, length);
        }
        outputStream.flush();
    }

    private int decompressBlock(byte[] block) throws IOException {
        System.out.println("Reading frequency table..");
        HuffmanFrequencies frequencies = HuffmanFrequencies.deserialize(inputStream);
        int blockLength = new DataInputStream(inputStream).readInt();

        System.out.printf("Decompressing block (%d B)...%n", blockLength);
        byte[] decodeBuffer = new byte[Byte.SIZE];
        HuffmanTree tree = new HuffmanTree(frequencies);
        for (int i = 0; i < blockLength;) {
            int bits = inputStream.read();
            if (bits == -1) {
                throw new IOException("Unexpected end of data.");
            }
            int bytes = tree.decode((byte) bits, decodeBuffer);
            for (int j = 0; j < bytes; j++) {
                block[i++] = decodeBuffer[j];
                if (i >= blockLength) {
                    break;
                }
            }
        }

        return blockLength;
    }

    public void decompress() throws IOException {
        byte[] block = new byte[BLOCK_SIZE];
        int length;
        while (true) {
            length = decompressBlock(block);
            outputStream.write(block, 0, length);

            inputStream.mark(1);
            if (inputStream.read() == -1) {
                break;
            }
            inputStream.reset();
        }
        outputStream.flush();
    }
}

class XCompress {
    public static void compress(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }

        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        VariableWidthEncoder.encode(0x40000000, new DataOutputStream(bo));
        byte[] bs = bo.toByteArray();
        int v = VariableWidthEncoder.decode(new DataInputStream(new ByteArrayInputStream(bs)));
        int testValue = 0x40000000;
        if (v != testValue) {
            System.out.printf("INEQUAL!!(READ != ACTU): %X != %X%n", v, testValue);
            throw new IllegalStateException("Oopsie encountered!");
        }

        for (int i = (Integer.MAX_VALUE / 2) - Short.MAX_VALUE * 128; i <= Integer.MAX_VALUE; i++) {
            if (i % (Short.MAX_VALUE * 128) == 0) {
                System.out.printf("%.2f %% done%n", ((double) i / (double) Integer.MAX_VALUE) * 100);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            VariableWidthEncoder.encode(i, new DataOutputStream(baos));
            byte[] barr = baos.toByteArray();
            int value = VariableWidthEncoder.decode(new DataInputStream(new ByteArrayInputStream(barr)));
            if (value != i) {
                System.out.printf("INEQUAL!!(READ != ACTU): %X != %X%n", value, i);
                throw new IllegalStateException("Oopsie encountered!");
            }
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {
            new HuffmanAlgorithm(inputStream, outputStream).compress();
        }
    }

    public static void decompress(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {

            new HuffmanAlgorithm(inputStream, outputStream).decompress();
        }
    }

    public static void printHelp() {
        System.out.println("You must specify a flag (-c, -d or -h) and a file path.");
    }

    public static void main(String[] args) throws IOException {
        String flag = args.length >= 1 ? args[0] : null;
        switch (flag) {
        case "-c":
            compress(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "-d":
            decompress(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "-h":
            printHelp();
            break;
        }
    }
}