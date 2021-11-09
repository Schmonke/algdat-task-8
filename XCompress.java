import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
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

class VariableWidthEncoding {
    private static final int BITS_PER_BYTE = 7;
    private static final int READ_BITS_MASK = (1 << BITS_PER_BYTE) - 1; // 0b01111111
    private static final int CONTINUE_BIT = 0b10000000;
    private static final int MAX_BYTES = (int) Math.ceil((double) Integer.SIZE / BITS_PER_BYTE);

    public static void encode(int value, OutputStream outputStream) throws IOException {
        new DataOutputStream(outputStream).writeInt(value);
        /*while (value != 0) {
            int bits = value & READ_BITS_MASK;
            value >>>= BITS_PER_BYTE;
            if (value != 0) {
                bits |= CONTINUE_BIT;
            }
            outputStream.write(bits);
        }*/
    }

    public static int decode(InputStream inputStream) throws IOException {
        return new DataInputStream(inputStream).readInt();
        /*int value = 0;
        int shift = 0;
        int b;
        int bytesRead = 0;

        while (bytesRead++ < MAX_BYTES && (b = inputStream.read()) != -1) {
            int bits = b & READ_BITS_MASK;
            value |= bits << shift;
            shift += BITS_PER_BYTE;
            if ((b & CONTINUE_BIT) == 0) {
                break;
            }
        }

        return value;*/
    }
}

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
            builder.append((bits & (1 << i)) >>> i);
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
        int b = (int) ((bits & (0xFF << shift)) >>> shift);
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
        outputStream.write(nonZeroFrequencies);
        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] != 0) {
                outputStream.write(i);
                VariableWidthEncoding.encode(frequencies[i], outputStream);
            }
        }
    }

    public static HuffmanFrequencies deserialize(InputStream inputStream) throws IOException {
        HuffmanFrequencies instance = new HuffmanFrequencies();
        int frequencyCount = inputStream.read() & 0xFF;
        for (int i = 0; i < frequencyCount; i++) {
            byte c = (byte) inputStream.read();
            int frequency = VariableWidthEncoding.decode(inputStream);
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
            int bit = (c >>> i) & 1;
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
        VariableWidthEncoding.encode(length, outputStream);

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
        int blockLength = VariableWidthEncoding.decode(inputStream);

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

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {
            //ByteArrayOutputStream lzOutputStream = new ByteArrayOutputStream();
            //new LempelZivAlgorithm(inputStream, outputStream).compress();
            //ByteArrayInputStream huffmanInputStream = new ByteArrayInputStream(lzOutputStream.toByteArray());
            new HuffmanAlgorithm(inputStream, outputStream).compress();
        }
    }

    public static void decompress(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }

        try (FileInputStream inputStream = new FileInputStream(args[0]);
                FileOutputStream outputStream = new FileOutputStream(args[1]);) {
            ByteArrayOutputStream huffmanOutputStream = new ByteArrayOutputStream();
            new HuffmanAlgorithm(inputStream, outputStream).decompress();
           // ByteArrayInputStream lzInputstream = new ByteArrayInputStream(huffmanOutputStream.toByteArray());
            //new LempelZivAlgorithm(lzInputstream, outputStream).decompress();
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