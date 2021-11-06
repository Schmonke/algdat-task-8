import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Stack;

class HuffmanTree {
    int[] frequencies = new int[256];
    BitSet[] encodeLut = new BitSet[256];
    Node root;


    public void increment(byte c) {
        frequencies[c & 0xFF]++;
    }

    private long[] createEncodeLut() {
        class Entry {
            public final Node node;
            public final BitSet bits;
            public Entry(Node node, BitSet bits) {
                this.node = node;
                this.bits = bits;
            }
        }
        BitSet[] encodeLut = new BitSet[256];
        Stack<Entry> stack = new Stack<>();

        stack.push(new Entry(root, new BitSet(0)));
    
        while (stack.size() > 0) {
            Entry entry = stack.pop();
            Node node = entry.node;
            if (node.isLeaf()) {
                BitSet bitSet = new BitSet(entry.bitCount);
                bitSet.
                encodeLut[entry.node.value & 0xFF]
                continue;
            }
            Node left = node.getChild(0);
            Node right = node.getChild(1);


            stack.push(new Entry(right, entry.bitCount + 1, (entry.bits << 1) | 1));
            stack.push(new Entry(left, entry.bitCount + 1, (entry.bits << 1) | 0));
        }

        return encodeLut;
    }

    private Node generateTree() {
        Comparator<Node> nodeComparator = Comparator.comparing(Node::getFrequency, Comparator.reverseOrder());
        PriorityQueue<Node> nodes = new PriorityQueue<>(frequencies.length, nodeComparator);

        for (int i = 0; i < frequencies.length; i++) {
            if (frequencies[i] != 0) {
                nodes.add(new Node(frequencies[i], (byte)i));
            }
        }
        
        while (nodes.size() > 1) {
            Node left = nodes.poll();
            Node right = nodes.poll();
            Node parent = new Node(left, right);
            nodes.add(parent);
        }
        
        return nodes.poll();
    }

    public void serialize(OutputStream outputStream) throws IOException {
        byte[] bytes = new byte[frequencies.length * Integer.SIZE];
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < frequencies.length; i++) {
            byteBuffer.putInt(frequencies[i]);
        }
        outputStream.write(bytes);
    }

    public void deserialize(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readNBytes(frequencies.length * Integer.SIZE);
        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        for (int i = 0; i < frequencies.length; i++) {
            frequencies[i] = byteBuffer.getInt();
        }
    }

    static class Node {
        public Node(Node left, Node right) {
            this.left = left;
            this.right = right;
            if (left != null) this.frequency += left.frequency;
            if (right != null) this.frequency += right.frequency;
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
                case 0: return left;
                case 1: return right;
                default: throw new IllegalArgumentException("pos must be 0 or 1");
            }
        }
    }
}

class HuffmanAlgorithm {
    private static final int READ_BUFFER_SIZE = 16384;

    byte[] compress(byte[] data) throws IOException {
        HuffmanTable huffTable = new HuffmanTable();
        byte[] bytes = new byte[READ_BUFFER_SIZE];
        int bytesRead = -1;

        for (int i = 0; i < data.length; i++) {
            huffTable.increment(data[i]);
        }



        return null;
    }

    void decompress() {

    }
}

class XCompress {
    public static void compress(String[] args) {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }

    }

    public static void decompress(String[] args) {
        if (args.length == 0) {
            System.out.println("No filepath was provided.");
        }
    }

    public static void printHelp() {
        System.out.println("You must specify a flag (-c, -d or -h) and a file path.");
    }

    public static void main(String[] args) {
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