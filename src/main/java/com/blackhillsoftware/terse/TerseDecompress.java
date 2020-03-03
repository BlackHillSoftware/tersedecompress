package com.blackhillsoftware.terse;

/**
  Copyright Contributors to the TerseDecompress Project.
  SPDX-License-Identifier: Apache-2.0
**/
/*****************************************************************************/
/* Copyright 2018        IBM Corp.                                           */
/*                                                                           */
/*   Licensed under the Apache License, Version 2.0 (the "License");         */
/*   you may not use this file except in compliance with the License.        */
/*   You may obtain a copy of the License at                                 */
/*                                                                           */
/*     http://www.apache.org/licenses/LICENSE-2.0                            */
/*                                                                           */
/*   Unless required by applicable law or agreed to in writing, software     */
/*   distributed under the License is distributed on an "AS IS" BASIS,       */
/*   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.*/
/*   See the License for the specific language governing permissions and     */
/*   limitations under the License.                                          */
/*****************************************************************************/
/*                                                                           */
/*  For problems and requirements please create a GitHub issue               */
/*  or contact: clientcenter@de.ibm.com                                      */
/*                                                                           */
/*****************************************************************************/
/*                                                                           */
/*  Author: Iain Lewis                          August 2004 (version 3)      */
/*                                                                           */
/*****************************************************************************/
/* Version 4 with editorial changes for publication as open source code      */
/*          Klaus Egeler, Boris Barth  (clientcenter@de.ibm.com)             */
/*****************************************************************************/




import java.io.*;


public class TerseDecompress {

    static final boolean DEBUG = false;  /* Control output of debug messages */

    /*
     * These appear to be control parameters to control i/o buffers, stack size etc
     * and hence will affect how much memory we use. The only ones actually used
     * in the current implementation are TreeSize and RecordMark
     */
    static final int  STACKSIZE = 0x07FF;     /* 2k - 1 */
    static final int  BUFFERSIZE = 0x07FF;    /* 2k - 1 */
    static final int  HASHSIZE = 0x0FFF;      /* 4k - 1 */
    static final int  TREESIZE = 0x1000;      /* 4k     */
    static final int  RECORDMARK = 257;       /*used in the file output functions*/

    /*
    Some Used in the unspack algorithm. I guess tuning affects the
    way which compression/decompression works
    Others are just useful constants
    */
    static final int BASE = 0;
    static final int CODESIZE = 257; /* 2**8+1, EOF, 256 Codepoints, RCM */
    static final int ENDOFFILE =0;
    /* This is the new line char that we write into the file. Not sure how
     * this will translate on the various platforms
     */
    static final char EOL = '\n';

    /*Useful Constants*/
    static final int NONE = -1;
     

    int [] Father = new int[TREESIZE];
    int [] CharExt = new int[TREESIZE];
    int [] Backward = new int[TREESIZE];
    int [] Forward = new int[TREESIZE];

    File InputFile;
    FileInputStream InputFileStream;
    BufferedInputStream BufferedStream;
    CompressedInputReader input;
    
    File OutputFile;
    FileOutputStream OutputFileStream;
    BufferedOutputStream OutputBufferedStream;
    DecompressedOutputWriter DecompressedOutputWriter;

    static final String DetailedHelp = new String(
          "Usage: \"TerseDecompress <input file> <output file> [-b]\"\n\n"
         +"Java TerseDecompress will decompress a file compressed using the terse program on z/OS\n"
         +"Default mode is text mode, which will attempt ebcdic -> ASCII conversion\n"
         +"The -b flag turns on binary mode, no conversion will be attempted\n"
         +"Please mail comments/suggestions to: clientcenter@de.ibm.com\n"
        );

    static final String Version = new String ("version 4, December 2018");


    boolean      ExamineFlag   = false; /* display contents of tersed file header   */
    boolean      FixedFlag     = false; /* host compatibility fixed block length    */
    boolean      HelpFlag      = false; /* true when extended help is requested     */
    boolean      InfoFlag      = false; /* true when statistics are requested       */
    boolean      QuietFlag     = false; /* true when quiet is selected              */
    boolean      VariableFlag  = true ; /* true for variable-length records         */
    int          XlateTableEbc = 37   ; /* ebcdic code page                         */
    int          XlateTableAsc = 437  ; /* ascii code page                          */
    boolean      XlateTableDef = true ; /* use default ALMCOPY table                */

    /*
     * These appear to be the flags for the terse file header. Some are used by 
     * CheckHeader() others are only used when writing a compressed file which
     * this implementation doesn't do.
     */
    static final int  FLAGUNDEF = 0x80;       /*  \                                                    */
    static final int  FLAGCC1   = 0x40;       /*   \                                               */
    static final int  FLAGCC2   = 0x20;       /*    \                                              */
    static final int  FLAGVBS   = 0x10;       /*     >-- values of Flags bits in HeaderRecord type */
    static final int  FLAGVS    = 0x08;         /*    /                                              */
    static final int  FLAGMVS   = 0x04;      /*   /                                               */
    static final int  FLAGRBITS = 0x03;       /*  /                                                */
    
    /*Defaults for dump types*/
    boolean TextFlag = true;
    boolean HostFlag = true;
    boolean SpackFlag = true;
    boolean DecodeFlag = false;
    boolean EncodeFlag = false; /* true when encoding is selected */


    /*
     * Check that the header of an input tersed file is consistent and set some of the static flags
     * associated with it.
     */

    public boolean CheckHeader(File InFile) {

        TerseHeader Header = new TerseHeader();

        FileInputStream filestream;
        DataInputStream datastream;

        try {

            filestream = new FileInputStream(InFile);
            datastream = new DataInputStream(filestream);

            Header.VersionFlag = datastream.readUnsignedByte();
            Header.VariableFlag = datastream.readUnsignedByte();
            Header.RecordLen1 = datastream.readUnsignedShort();
            Header.Flags = datastream.readUnsignedByte();
            Header.Ratio = datastream.readUnsignedByte();
            Header.BlockSize = datastream.readUnsignedShort();
            Header.RecordLen2 = Utils.readUnsignedInt(datastream);
            datastream.close();

        } catch (Exception e) {
            System.err.println("Error while reading header from input file");
            System.err.println(e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        if (DEBUG) {
            System.out.println("Header is:\n" +Header);
        }

        switch (Header.VersionFlag) {
        case 0x01: /* native binary mode, 4 byte header, versions 1.2+ */
            if (Header.VariableFlag    != 0x89)
                return false;
            if (Utils.Lo( Header.RecordLen1) != 0x69)
                return false;
            if (Utils.Hi( Header.RecordLen1) != 0xA5)
                return false;
            if (HostFlag) {
                if (TextFlag) { /* defaults */
                    HostFlag = false; /* autoswitch to native mode */
                    TextFlag = false;
                } else {
                    return false; /* header conflicts with explicit "/b" switch */
                }
            }
            break;

        case 0x02: /* host  PACK compatibility mode, 12 byte header */
            SpackFlag = false;
            if ((Header.VariableFlag != 0x00) && (Header.VariableFlag != 0x01))
                return false;
            if ( (Header.RecordLen1 == 0) ^  (Header.RecordLen2 != 0))
                return false;
            if ((Header.Flags & FLAGMVS) == 0) {
                if (    Header.Flags != 0) return false;
                if (    Header.Ratio != 0) return false;
                if (Header.BlockSize != 0) return false;
            }
            if (!HostFlag) {
                return false; /* header conflicts with explicit "-n" switch */
            }
            break;

        case 0x05: /* host SPACK compatibility mode, 12 byte header */
            if ((Header.VariableFlag != 0x00) && (Header.VariableFlag != 0x01))
                return false;
            if (  (Header.RecordLen1 == 0) ^  (Header.RecordLen2 != 0) )
                return false;
            if ((Header.Flags & FLAGMVS) == 0) {
                if ( Header.Flags != 0)
                    return false;
                if ( Header.Ratio != 0)
                     return false;
                if (Header.BlockSize != 0)
                    return false;
            }
            if (!HostFlag) {
                return false; /* header conflicts with explicit "-n" switch */
            }
            break;

        case 0x07: /* native binary mode, 4 byte header, versions 1.1- */
            if (Header.VariableFlag      != 0x89)
                return false;
            if (Utils.Lo( Header.RecordLen1  ) != 0x69)
                return false;
            if (Utils.Hi( Header.RecordLen1  ) != 0xA5)
                return false;
            if (HostFlag) {
                if (TextFlag) { /* defaults */
                    HostFlag = false; /* autoswitch to native mode */
                    TextFlag = false;
                } else {
                    return false; /* header conflicts with explicit "/b" switch */
                }
            }
            break;
        default:
            return false;
        }

        return true;

    }


    StackType Stack = new StackType();


    public void PutChars(int X, DecompressedOutputWriter outstream) throws IOException {
        Stack.Head = 0;

        while (true) {
            while (X > CODESIZE) {
                Stack.Head++;
                Stack.Data[Stack.Head] = Tree[X].Right;
                X = Tree[X].Left;
            }
            outstream.PutChar( X );

            if (Stack.Head > 0) {
                X = Stack.Data[Stack.Head];
                Stack.Head--;
            } else
                break;
        }

    }


    int TreeAvail;

    TreeRecord Tree[] = new TreeRecord[TREESIZE+1];


    public void TreeInit() {

        for (int i =0; i < Tree.length; i ++) {
            Tree[i] = new TreeRecord();
        }

        int init_index = BASE;
        while (init_index <= CODESIZE) {
            Tree[init_index].Left  = NONE;
            Tree[init_index].Right = init_index++;
        }
        for (init_index = CODESIZE+1; init_index <= TREESIZE-1; init_index++) {
            Tree[init_index].NextCount  = init_index+1;
            Tree[init_index].Left  = NONE;
            Tree[init_index].Right = NONE;
        }
        Tree[TREESIZE].NextCount = NONE;
        Tree[BASE].NextCount = BASE;
        Tree[BASE].Back = BASE;
        for (init_index = 1; init_index <= CODESIZE; init_index++) {
            Tree[init_index].NextCount = NONE;
        }
        TreeAvail = CODESIZE+1;
    }


    /*
     * The following methods are all used by the spack decode algorithm
     * The precise use of all of them is unknown!!
     */

    int lru_p = 0, lru_q = 0, lru_r = 0;

    public void LruKill() {
        lru_p = Tree[0].NextCount;
        lru_q = Tree[lru_p].NextCount;
        lru_r = Tree[lru_p].Back;
        Tree[lru_q].Back = lru_r;
        Tree[lru_r].NextCount = lru_q;
        DeleteRef(Tree[lru_p].Left);
        DeleteRef(Tree[lru_p].Right);
        Tree[lru_p].NextCount = TreeAvail;
        TreeAvail = lru_p;
    }

    public void DeleteRef(int dref) {
        if (Tree[dref].NextCount == -1) {
            LruAdd(dref);
        } else {
            Tree[dref].NextCount++;
        }
    }


    int lru_back=0;

    public void LruAdd(int lru_next) {
        lru_back = Tree[BASE].Back;
        Tree[lru_next].NextCount = BASE;
        Tree[BASE].Back = lru_next;
        Tree[lru_next].Back = lru_back;
        Tree[lru_back].NextCount = lru_next;
    }


    int node =0;

    public int GetTreeNode() {
        node = TreeAvail;
        TreeAvail = Tree[node].NextCount;
        return node;
    }


    int forwards = 0, prev = 0;

    public void BumpRef(int bref) {
        if (Tree[bref].NextCount < 0) {
            Tree[bref].NextCount--;
        } else {
            forwards = Tree[bref].NextCount;
            prev = Tree[bref].Back;
            Tree[prev].NextCount = forwards;
            Tree[forwards].Back = prev;
            Tree[bref].NextCount = -1;
        }
    }


    /*
     * Decode logic for a file compressed with the spack algorithm
     * Inputstream should wrap the compressed data, outputstream is where we write
     * the decompressed data to.
     */

    public void decodeSpack( CompressedInputReader input, OutputStream outstream) throws IOException {

        if (DEBUG) {
            System.out.println("Text Flag is: " + TextFlag);
            System.out.println("Host Flag is: " + HostFlag);
            System.out.println("Spack Flag is: " + SpackFlag);
            System.out.println("Decode Flag is: " + DecodeFlag);
            System.out.println("Encode Flag is: " + EncodeFlag);
            System.out.println("Examine Flag is: " + ExamineFlag);
            System.out.println("Fixed Flag is: " + FixedFlag);
            System.out.println("Variable Flag is: " + VariableFlag);
            System.out.println("Help Flag is: " + HelpFlag);
        }

        long RecordLength = 256;
               
        TreeAvail = 0;
        int N = 0, G = 0, H = 0;
        int H1 = 0, H2 = 0, H3 = 0, H4 = 0, H5 = 0, H6 = 0, H7 = 0;
        if (HostFlag) { /* examine vm header */
            H1 = input.FileGetRequired(8); /* terse version */
            H2 = input.FileGetRequired(8); /* variable-length record flag */
            H3 = input.FileGetRequired(16); /* record length */
            H4 = input.FileGetRequired(16); /* filler */
            H5 = input.FileGetRequired(16); /* filler */
            H6 = input.FileGetRequired(16); /* filler */
            H7 = input.FileGetRequired(16); /* filler */
            Utils.AssertString( "Invalid File Header: Terse Version Flag", H1 == 5);
            Utils.AssertString( "Invalid File Header: Fixed/Variable Block Flag", (H2 == 0) || (H2 == 1));
            if (H3 == 0) {
            	Utils.AssertString( "Invalid File Header: Zero Record Length", (H6 != 0) || (H7 != 0));
            } else {
            	Utils.AssertString( "Invalid File Header: Ambiguous Record Length", (H6 == 0) && (H7 == 0));
            }
            if ((H4 & 0x0400) == 0) {
            	Utils.AssertString( "Invalid File Header: Non-Zero Filler 1", H4 == 0);
            	Utils.AssertString( "Invalid File Header: Non-Zero Filler 2", H5 == 0);
            }
            VariableFlag = (H2 == 1);
            if (H3 == 0) {
                RecordLength = ((long) H6 << 16) | (long) H7;
            } else {
                RecordLength = (long) H3;
            }
        } else {
            H1 = input.FileGetRequired(8); /* terse version */
            H2 = input.FileGetRequired(8); /* validation flag 1 */
            H3 = input.FileGetRequired(8); /* validation flag 2 */
            H4 = input.FileGetRequired(8); /* validation flag 3 */
            Utils.AssertString( "Invalid File Header: Terse Version Flag"   , (H1 == 1) || (H1 == 7));
            Utils.AssertString( "Invalid File Header: Validation Flag One"  , H2 == 0x89);
            Utils.AssertString( "Invalid File Header: Validation Flag Two"  , H3 == 0x69);
            Utils.AssertString( "Invalid File Header: Validation Flag Three", H4 == 0xA5);
        }

        DecompressedOutputWriter = new DecompressedOutputWriter(outstream, RecordLength, HostFlag, TextFlag, VariableFlag);
        
        TreeInit();
        Tree[TREESIZE-1].NextCount = NONE;

        H = input.GetBlok();
        PutChars( H, DecompressedOutputWriter);

        while (H != ENDOFFILE) {

            G = input.GetBlok();
            if (TreeAvail == NONE) {
                LruKill();
            }
            PutChars(G, DecompressedOutputWriter);
            N = GetTreeNode();
            Tree[N].Left = H;
            Tree[N].Right = G;
            BumpRef(H);
            BumpRef(G);
            LruAdd(N);
            H = G;
        }

    }


    /* 
     * Decode the file on the other end of the input stream using a non spack decode.
     * Write the output to the output stream.
     * Assume that both streams are initialized and ready to be read from/written to.
     */
    public void decodeNonSpack(CompressedInputReader input, OutputStream outstream) throws IOException {

        if (DEBUG) {
            System.out.println("decodeNonSpack");
        }

        if (DEBUG) {
            System.out.println("Text Flag is: " + TextFlag);
            System.out.println("Host Flag is: " + HostFlag);
            System.out.println("Spack Flag is: " + SpackFlag);
            System.out.println("Decode Flag is: " + DecodeFlag);
            System.out.println("Encode Flag is: " + EncodeFlag);
            System.out.println("Examine Flag is: " + ExamineFlag);
            System.out.println("Fixed Flag is: " + FixedFlag);
            System.out.println("Variable Flag is: " + VariableFlag);
            System.out.println("Help Flag is: " + HelpFlag);
        }

        long RecordLength = 256;
        
        int  H1 = 0, H2 = 0, H3 = 0, H4 = 0, H5 = 0, H6 = 0, H7 = 0;
        int x = 0, d = 0, y = 0, q = 0, r = 0, e = 0, p = 0, h = 0;

        H2 = 1 + CodePages.AscToEbcDef[' '];

        for (H1 = 258; H1 < 4096; H1++) {
          Father [H1] = H2;
          CharExt[H1] = 1 + CodePages.AscToEbcDef[' '];
          H2 = H1;
        }

        for (H1 = 258; H1 < 4095; H1++) {
          Backward[H1+1] = H1  ;
          Forward [H1  ] = H1+1;
        }

        Backward [0] = 4095;
        Forward [0] = 258;
        Backward [258] = 0;
        Forward [4095] = 0;

        if (DEBUG) {
            System.out.println("Done setup in Decode2. About to read from the file");
        }



        H1 = input.FileGetRequired(8); /* terse version */
        H2 = input.FileGetRequired(8); /* variable-length record flag */
        H3 = input.FileGetRequired(16); /* record length */
        H4 = input.FileGetRequired(16); /* filler */
        H5 = input.FileGetRequired(16); /* filler */
        H6 = input.FileGetRequired(16); /* filler */
        H7 = input.FileGetRequired(16); /* filler */


        if (DEBUG) {
            System.out.println("Have read header info again.");
            System.out.println("h1 is " +H1 +" h2 is " +H2 +" h3 is " +H3 +" h4 is " +H4 +" h5 is " +H5 +" h6 is " +H6 +" h7 is " +H7);
        }



        Utils.AssertString( "Invalid File Header: Terse Version Flag", H1 == 2);
        Utils.AssertString( "Invalid File Header: Fixed/Variable Block Flag", (H2 == 0) || (H2 == 1));
        if (H3 == 0) {
        	Utils.AssertString( "Invalid File Header: Zero Record Length", (H6 != 0) || (H7 != 0));
        } else {
        	Utils.AssertString( "Invalid File Header: Ambiguous Record Length", (H6 == 0) && (H7 == 0));
        }
        if ((H4 & 0x0400) == 0) {
        	Utils.AssertString( "Invalid File Header: Non-Zero Filler 1", H4 == 0);
        	Utils.AssertString( "Invalid File Header: Non-Zero Filler 2", H5 == 0);
        }

        if (DEBUG) {
            System.out.println("Checked the headers");
        }


        VariableFlag = (H2 == 1);
        if (H3 == 0) {
            RecordLength = ( ((long)H6) << 16) | ((long)H7);
        } else {
            RecordLength = (long)H3;
        }

        DecompressedOutputWriter = new DecompressedOutputWriter(outstream, RecordLength, HostFlag, TextFlag, VariableFlag);
        
        x=0;
        d = input.GetBlok();

        while (d != 0) {
            h = 0;
            y = Backward[0];
            q = Backward[y];
            Backward[0] = q;
            Forward [q] = 0;
            h = y;
            p = 0;
            while (d > 257) {
                q = Forward [d];
                r = Backward[d];
                Forward [r] = q;
                Backward[q] = r;
                Forward [d] = h;
                Backward[h] = d;
                h = d;
                e = Father[d];
                Father[d] = p;
                p = d;
                d = e;
            }
            q = Forward[0];
            Forward [y] = q;
            Backward[q] = y;
            Forward [0] = h;
            Backward[h] = 0;
            CharExt [x] = d;
            DecompressedOutputWriter.PutChar(d);
            x = y;
            while (p != 0) {
                e = Father[p];
                DecompressedOutputWriter.PutChar( CharExt[p]);
                Father[p] = d;
                d = p;
                p = e;
            }
            Father[y] = d;
            d = input.GetBlok();
        }

        return;
    }

    /*
     * Currently working towards a decompress in binary mode only implementation.
     * We assume that we get "TerseDecompress <input file> <output file>". Otherwise exit with
     * an error message.
     */

    public void process (String args[]) throws IOException {

        if (args.length == 0 || args.length > 3) {
            System.out.println(DetailedHelp);
            System.out.println(Version);
            System.exit(0);
            /*
            if (args[0].equals("-b")) {
                TextFlag = false;
                System.out.println("Using binary decompression. No ascii/ebcdic conversion will be performed");
            } else {
                System.out.println("Unsupported option: " +args[0]);
            }
        } else {
            System.out.println("Text mode decompression. Ascii/ebcdic conversion will be done");
            */
        }

        if (args.length == 1) {
            System.out.println(DetailedHelp);
            System.out.println(Version);
            System.exit(0);
        }

        if(DEBUG) {
            System.out.println("Input args exist. About to check header");
        }


        boolean header_rv;
        /*Apparently need to do this to set the spack flag, but the results are thrown away*/
        header_rv = CheckHeader(new File(args[0]));

        if (!header_rv) {
            System.err.println("Failed to read header of input file.");
            System.exit(1);
        }
        
        if(DEBUG) {
            System.out.println("Header is checked. About to open streams.");
        }


        try {
            InputFile = new File(args[0]);
            InputFileStream = new FileInputStream(InputFile);
            BufferedStream = new BufferedInputStream(InputFileStream);
            input = new CompressedInputReader(BufferedStream);


        } catch (Exception e) {
            System.err.println("Can't open input file: " +InputFile);
            System.exit(1);
        }

        try {
            OutputFile = new File(args[1]);
            OutputFileStream = new FileOutputStream(OutputFile);
            OutputBufferedStream = new BufferedOutputStream(OutputFileStream);
 
        } catch (Exception e) {
            System.err.println("Can't open output file: " +OutputFile);
            System.exit(1);
        }

        if (args.length == 3) {
            TextFlag = false;
            System.out.println("3rd argument (" +args[2] +") found, so using binary mode.");
        }


        if(DEBUG) {
            System.out.println("Input and output streams opened. About to decode");
        }

        System.out.println("Attempting to decompress input file (" +InputFile +") to output file (" +OutputFile +")");

        if (!SpackFlag) {
            decodeNonSpack(input, OutputBufferedStream);
        } else {
            decodeSpack(input, OutputBufferedStream);
        }

        try {
            BufferedStream.close();
            OutputBufferedStream.flush();
            OutputBufferedStream.close();
        } catch (IOException e) {
            System.err.println("Error while closing file streams");
            System.err.println(e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Processing completed");
        if (DEBUG) {
            System.err.println("Read " + input.red + " bytes");
        }

    }

    public static void main (String args[]) throws IOException {

        TerseDecompress tersed = new TerseDecompress();
        tersed.process(args);
    }

}