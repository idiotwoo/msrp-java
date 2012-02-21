/*
 * Copyright � Jo�o Antunes 2008 This file is part of MSRP Java Stack.
 * 
 * MSRP Java Stack is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * MSRP Java Stack is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with MSRP Java Stack. If not, see <http://www.gnu.org/licenses/>.
 */
package msrp;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Observable;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import msrp.Connections;
import msrp.Session;
import msrp.exceptions.ConnectionParserException;
import msrp.exceptions.ConnectionReadException;
import msrp.exceptions.ConnectionWriteException;
import msrp.exceptions.IllegalUseException;
import msrp.messages.Message;
import msrp.utils.NetworkUtils;
import msrp.utils.TextUtils;

/**
 * This class represents an MSRP connection.
 * 
 * It has one pair of threads associated for writing and reading.
 * 
 * It is also responsible for some parsing, including: Identifying MSRP
 * transaction requests and responses; Pre-parsing - identifying what is the
 * content of the transaction from what isn't; Whenever a transactions is found,
 * parse its data using the Transaction's parse method;
 * 
 * @author Jo�o Andr� Pereira Antunes
 */
class Connection extends Observable implements Runnable
{
    /**
     * The logger associated with this class
     */
    private static final Logger logger =
        LoggerFactory.getLogger(Connection.class);

    public static final int OUTPUTBUFFERLENGTH = 2048;

    public Connection(SocketChannel newSocketChannel)
        throws URISyntaxException
    {
        socketChannel = newSocketChannel;
        random = new Random();
        Socket socket = socketChannel.socket();
        URI newLocalURI =
            new URI("msrp", null, socket.getInetAddress().getHostAddress(),
                socket.getLocalPort(), null, null, null);
        localURI = newLocalURI;
        transactionManager = new TransactionManager(this);
        // this.addObserver(transactionManager);

    }

    /**
     * Create a new connection object.
     * This connection will create a socket and bind itself to a free port.
     * 
     * @param address hostname/IP used to bound the new MSRP socket
     * @throws URISyntaxException there was a problem generating the connection
     *             dependent part of the URI
     * @throws IOException if there was a problem with the creation of the
     *             socket
     */
    public Connection(InetAddress address) throws URISyntaxException, IOException
    {
        transactionManager = new TransactionManager(this);
        random = new Random();
        // activate the connection:

        if (NetworkUtils.isLinkLocalIPv4Address(address))
        {
            logger.info("Connection: given address is a local one: " + address);
        }

        // bind a socket to a local TEMP port.
        socketChannel = SelectorProvider.provider().openSocketChannel();
        Socket socket = socketChannel.socket();
        InetSocketAddress socketAddr = new InetSocketAddress(address, 0);
        socket.bind(socketAddr);

        // fill the localURI variable that contains the uri parts that are
        // associated with this connection (scheme[protocol], host and port)
        URI newLocalURI =
            new URI("msrp", null, address.getHostAddress(), socket.getLocalPort(),
            		null, null, null);
        localURI = newLocalURI;
        // this.addObserver(transactionManager);
    }

    private TransactionManager transactionManager;

    /**
     * private field used mainly to generate new session uris this method should
     * contain all the uris of the sessions associated with this connection TODO
     * 
     * FIXME ?! check to see if this sessions is needed even though the
     * associatedSessions on transactionManager exists and should contain the
     * same information
     */
    private HashSet<URI> sessions = new HashSet<URI>();

    private SocketChannel socketChannel = null;

    protected Random random;

    protected URI localURI = null;

    /**
     * @uml.property name="_connections"
     * @uml.associationEnd inverse="connection1:msrp.Connections"
     */
    protected TransactionManager getTransactionManager()
    {
        return transactionManager;
    }

    /**
     * @param localURI sets the local URI associated with the connection
     */
    protected void setLocalURI(URI localURI)
    {
        this.localURI = localURI;
    }

    protected boolean isEstablished()
    {
        if (socketChannel == null)
            return false;
        return socketChannel.isConnected();
    }

    /**
     * 
     * @return returns the associated local uri (relevant parts of the uri for
     *         the connection)
     */
    protected URI getLocalURI()
    {
        return localURI;
    }

    protected HashSet<URI> getSessionURIs()
    {
        return sessions;
    }

    /**
     * Generates the new URI and validates it against the existing URIs of the
     * sessions that this connection handles
     * 
     * @throws URISyntaxException If there is a problem with the generation of
     *             the new URI
     */
    protected synchronized URI generateNewURI() throws URISyntaxException
    {
        URI newURI = newUri();

        /* The generated URI must be unique, if not, generate another.
         * This is what the following while does
         */
        int i = 0;
        while (sessions.contains(newURI))
        {
            i++;
            newURI = newUri();
        }
        sessions.add(newURI);
        logger.trace("generated new URI, value of i=" + i);
        return newURI;
    }

    /** Generate a new local URI with a unique session-path.
     * @return the generated URI
     * @throws URISyntaxException @see java.net.URI
     */
    protected URI newUri() throws URISyntaxException {
        byte[] randomBytes = new byte[8];

        generateRandom(randomBytes);

        if (logger.isTraceEnabled())
	        logger.trace("Random bytes generated: "
	            + (new String(randomBytes, TextUtils.utf8))
	            + ":END");

        // Generate new using current local URI.
        return
            new URI(localURI.getScheme(), localURI.getUserInfo(),
            		localURI.getHost(), localURI.getPort(),
            		 "/" + (new String(randomBytes, TextUtils.utf8))
            		 + ";tcp", localURI.getQuery(), localURI.getFragment());
    }

    // IMPROVE add the rest of the unreserved characters according rfc3986 (-._~)
    // IMPROVE speed by not doing so much calls to the Random class
    /**
     * Generates a number of random alpha-numeric codes in US-ASCII
     * 
     * @param byteArray	array that receives the newly generated bytes.
     *            	The number of generated bytes is given by the length of
     *            	the array.
     */
    protected void generateRandom(byte[] byteArray)
    {
        random.nextBytes(byteArray);
        for (int i = 0; i < byteArray.length; i++)
        {
            if (byteArray[i] < 0)
                byteArray[i] *= -1;

            while (!((byteArray[i] >= 65 && byteArray[i] <= 90)
                || (byteArray[i] >= 97 && byteArray[i] <= 122)
                || (byteArray[i] <= 57 && byteArray[i] >= 48)))
            {
                if (byteArray[i] > 122)
                    byteArray[i] -= random.nextInt(byteArray[i]);
                if (byteArray[i] < 48)
                    byteArray[i] += random.nextInt(5);
                else
                    byteArray[i] += random.nextInt(10);
            }
        }
    }

    /**
     * Returns if the socket associated with the connection is bound
     * 
     * @return
     */
    protected boolean isBound()
    {
        if (socketChannel == null)
            return false;
        return socketChannel.socket().isBound();
    }

    /**
     * @uml.property name="_session"
     * @uml.associationEnd inverse="_connectoin:msrp.Session"
     */
    private msrp.Session _session = null;

    /**
     * Getter of the property <tt>_session</tt>
     * 
     * @return Returns the _session.
     * @uml.property name="_session"
     */
    public msrp.Session get_session()
    {
        return _session;
    }

    /**
     * Getter of the property <tt>_transactions</tt>
     * 
     * @return Returns the _transactions.
     * @uml.property name="_transactions"
     */

    /** close this connection/these threads
     * 
     */
    public void close()
    {
    	if (closing)
    		return;						// already closed
    	closing = true;
    	writeThread.notifyAll();
    	try {
			socketChannel.close();
		} catch (IOException e) {
			/* empty */;
		}
    }

    /**
     */
    public void messageInterrupt(Message message)
    {
    }

    /**
     */
    public void newTransaction(Session session, Message message,
        TransactionManager transactionManager, String transactionCode)
    {
    }

    /**
     * @desc - Read (reads from the stream of the socket)
     * @desc - Validation of what is being read
     * @desc - Misc. Interrupts due to read errors (mem, buffers etc)
     * 
     */
    public void read()
    {
    }

    /**
     */
    public void sessionClose(Session session)
    {
    }

    /**
     * Setter of the property <tt>_session</tt>
     * 
     * @param _session The _session to set.
     * @uml.property name="_session"
     */
    public void set_session(msrp.Session _session)
    {
        this._session = _session;
    }

    /**
     * Defines which block of data should be sent over the connection, according
     * to the priority Currently it's implemented an FIFO by the transaction,
     * however it could be later used
     * 
     * @return
     * @throws Exception
     */
    public Connection()
    {
    }

    protected boolean closing = false;	// connection closing?

    private Thread writeThread = null;
    private Thread readThread = null;

    private void writeCycle() throws ConnectionWriteException
    {
        /*
         * TODO FIXME should remove this line here when we get a better model
         * for the threads
         */
        Thread.currentThread().setName("Connection: " + localURI + " writeCycle thread");

        byte[] outData = new byte[OUTPUTBUFFERLENGTH];
        ByteBuffer outByteBuffer = ByteBuffer.wrap(outData);

        int wroteNrBytes = 0;
        while (!closing)
        {
            try
            {
                if (transactionManager.hasDataToSend())
                {
                    int toWriteNrBytes;

                    outByteBuffer.clear();
                    // toWriteNrBytes = transactionManager.dataToSend(outData);
                    // FIXME remove comment and change method name after the
                    // tests go well
                    toWriteNrBytes = transactionManager.dataToSend2(outData);

                    outByteBuffer.limit(toWriteNrBytes);
                    wroteNrBytes = 0;
                    while (wroteNrBytes != toWriteNrBytes)
                        wroteNrBytes += socketChannel.write(outByteBuffer);
                }
                else
                {
                    // TODO FIXME do this in another way, maybe with notify!
                    synchronized (writeThread)
                    {
                        writeThread.wait(2000);
                    }
                }
            }
            catch (Exception e)
            {
            	if (!closing)
            		throw new ConnectionWriteException(e);
            }
        }
    }

    /**
     * Used to pre-parse the received data by the read cycle
     * 
     * @see #readCycle()
     * @see PreParser#preParse(byte[])
     */
    private PreParser preParser = new PreParser();

    private void readCycle() throws ConnectionReadException
    {
        byte[] inData = new byte[OUTPUTBUFFERLENGTH];
        ByteBuffer inByteBuffer = ByteBuffer.wrap(inData);
        int readNrBytes = 0;
        while (readNrBytes != -1 && !closing)
        {
            inByteBuffer.clear();
            try
            {
                readNrBytes = socketChannel.read(inByteBuffer);

                if (readNrBytes != -1 && readNrBytes != 0)
                {
                    inByteBuffer.flip();
                    preParser.preParse(inData, inByteBuffer.limit());
                }
            }
            catch (Exception e)
            {
            	if (!closing)
            		throw new ConnectionReadException(e);
            }
        }
    }

    /**
     * Constantly receives and sends new transactions
     */
    public void run()
    {
        // Sanity checks
        if (!this.isBound() && !this.isEstablished())
        {
            // if the socket is not bound to a local address or is
            // not connected, it shouldn't be running
            logger.error("Error!, Connection shouldn't be running yet");
            return;
        }
        if (!this.isEstablished() || !this.isBound())
        {
            logger.error("Error! got a unestablished either or "
                + "unbound connection");
            return;
        }

        if (writeThread == null && readThread == null)
        {
            writeThread = Thread.currentThread();
            readThread = new Thread(ioOperationGroup, this);
            readThread.setName("Connection: " + localURI + " readThread");
            readThread.start();

        }
        try
        {
            if (writeThread == Thread.currentThread())
            {
                writeCycle();
                writeThread = null;
            }
            if (readThread == Thread.currentThread())
            {
                readCycle();
                readThread = null;
            }
        }
        catch (Exception e)
        {
        	logger.error(e.getLocalizedMessage());
        }
        return;
    }

    /**
     * Pre-parse incoming data. Main purpose
     * is to correctly set the receivingBinaryData variable so that it accurately
     * states whether this connection is receiving binary data or not
     * 
     */
    class PreParser
    {
        /**
         * Variable that tells if this instance of the connection is currently
         * receiving header data or body data, if it's false it's because it
         * should be receiving header data.
         * 
         * This variable is changed by the preParser method and read by the
         * parser method.
         * 
         * @see PreParser#preParser(byte[])
         * @see #parser(String)
         */
        private boolean receivingBodyData = false;

        /**
         * Variable used by the method preParser in order to ascertain the state
         * of the machine
         */
        private short parserState = 0;

        /**
         * This variable is used to save the possible start of the end-line.
         * It's maximum size can is of: 2 bytes for the initial CRLF after the
         * data; 7 bytes for the '-' char; 32 bytes for the transactid; 1 byte
         * for the continuation flag; 2 bytes for the ending CRLF; + Total: 44
         * bytes.
         * 
         */
        private ByteBuffer smallBuffer = ByteBuffer.allocate(44);

        /**
         * Method that implements a small state machine in order to identify if
         * the incomingData belongs to the headers or to the body. This method
         * is responsible for changing the value of the variable
         * receivingBinaryData to the correct value and then calling the parser.
         * 
         * @param incomingData the incoming byte array that contains the data
         *            received
         * @param length the number of bytes of the given incomingData array to
         *            be considered for preprocessing.
         * @throws ConnectionParserException if there ocurred an exception while
         *             calling the parser method of this class
         * @see #receivingBinaryData
         */
        private void preParse(byte[] incomingData, int length)
            throws ConnectionParserException
        {
            ByteBuffer bufferIncData = ByteBuffer.wrap(incomingData, 0, length);
            /*
             * this variable keeps the index of the last time the data was sent
             * to be processed
             */
            int indexLastTimeChanged = 0;

            if (smallBuffer.position() != 0)
            {
                /* in case we have data to append, append it */
                int positionSmallBuffer = smallBuffer.position();
                byte[] incAppendedData =
                    new byte[(positionSmallBuffer + incomingData.length)];
                smallBuffer.flip();
                smallBuffer.get(incAppendedData, 0, positionSmallBuffer);
                smallBuffer.clear();
                bufferIncData.get(incAppendedData, positionSmallBuffer, length);
                /*
                 * now we substitute the old data for the new one with the
                 * appended bytes
                 */
                bufferIncData = ByteBuffer.wrap(incAppendedData);

                /*
                 * now we must set forward the position of the buffer so that it
                 * doesn't read again the stored bytes
                 */
                bufferIncData.position(positionSmallBuffer);

            }
            while (bufferIncData.hasRemaining())
            {
                /*
                 * we have two distinct points of start for the algorithm,
                 * either we are in the binary state or in the text state
                 */
                if (!receivingBodyData)
                {
                    switch (parserState)
                    {
                    case 0:
                        if (bufferIncData.get() == '\r')
                        {
                            parserState = 1;
                        }
                        break;
                    case 1:
                        if (bufferIncData.get() == '\n')
                        {
                            parserState = 2;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 2:
                        if (bufferIncData.get() == '\r')
                        {
                            parserState = 3;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 3:
                        if (bufferIncData.get() == '\n')
                        {
                            parserState = 0;
                            /*
                             * if the state (binary or text) changed since the
                             * beginning of the preprocessing of the data, then
                             * we should call the parser with the data we have
                             * so far
                             */
                            parser(
                                bufferIncData.array(),
                                indexLastTimeChanged,
                                bufferIncData.position() - indexLastTimeChanged,
                                receivingBodyData);
                            indexLastTimeChanged = bufferIncData.position();
                            if (incomingTransaction == null)
                            {
                                // TODO FIXME ?! maybe throw an exception, if we
                                // are here it means that the protocol was
                                // violated
                                logger
                                    .error("The incomingTransaction is null on the preParser in a state where it should not be (receiving binary data)!");
                            }

                            receivingBodyData = true;
                        }
                        else
                        {
                            rewindOnePosition(bufferIncData);
                            parserState = 0;
                        }
                    }// switch (parserState)
                }// if (!receivingBinaryData)
                else
                {
                    /*
                     * if we are receiving binary data we should/must be in a
                     * transaction
                     */

                    switch (parserState)
                    {
                    case 0:
                        if (bufferIncData.get() == '\r')
                        {
                            parserState = 1;
                        }
                        break;
                    case 1:
                        if (bufferIncData.get() == '\n')
                        {
                            parserState = 2;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 2:
                        if (bufferIncData.get() == '-')
                        {
                            parserState = 3;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 3:
                        if (bufferIncData.get() == '-')
                        {
                            parserState = 4;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 4:
                        if (bufferIncData.get() == '-')
                        {
                            parserState = 5;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 5:
                        if (bufferIncData.get() == '-')
                        {
                            parserState = 6;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 6:
                        if (bufferIncData.get() == '-')
                        {
                            parserState = 7;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 7:

                        if (bufferIncData.get() == '-')
                        {
                            parserState = 8;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    case 8:
                        if (bufferIncData.get() == '-')
                        {
                            parserState = 9;
                        }
                        else
                        {
                            parserState = 0;
                            rewindOnePosition(bufferIncData);
                        }
                        break;
                    default:
                        if (parserState >= 9)
                        {
                            /*
                             * at this point if we don't have any
                             * incomingTransaction associated with this
                             * connection then something wrong happened, log it!
                             */
                            if (incomingTransaction == null)
                            {
                                // log the fact that the incoming
                                // transaction was null
                                // TODO FIXME ?! maybe throw an exception, if we
                                // are here it means that the protocol was
                                // violated
                                logger
                                    .error("The incomingTransaction is null on the preParser in a state where it should not be (receiving binary data)!");
                            }
                            else if (incomingTransaction.getTID().length() == (parserState - 9))
                            {
                                /*
                                 * it means that we reached the end of the
                                 * transact-id then we must lookout for a valid
                                 * continuation flag
                                 */
                                char incChar = (char) bufferIncData.get();
                                if (incChar == '+' || incChar == '$'
                                    || incChar == '#')
                                {
                                    parserState++;
                                }
                                else
                                {
                                    rewindOnePosition(bufferIncData);
                                    parserState = 0;
                                }
                            }
                            else if ((parserState - 9) > incomingTransaction
                                .getTID().length())
                            {
                                if ((parserState - 9) == incomingTransaction
                                    .getTID().length() + 1)
                                {
                                    /* we should expect the CR here */
                                    if (bufferIncData.get() == '\r')
                                    {
                                        parserState++;
                                    }
                                    else
                                    {
                                        parserState = 0;
                                        rewindOnePosition(bufferIncData);
                                    }
                                }
                                else if ((parserState - 9) == incomingTransaction
                                    .getTID().length() + 2)
                                {
                                    /* we should expect the LF here */
                                    if (bufferIncData.get() == '\n')
                                    {
                                        parserState++;
                                        /*
                                         * so from now on we are on text mode
                                         * again so we process all of the binary
                                         * data we have so far excluding "\r\n"
                                         * and "end-line" that later must be
                                         * parsed as text
                                         */
                                        bufferIncData.position(bufferIncData
                                            .position()
                                            - parserState);
                                        parser(bufferIncData.array(),
                                            indexLastTimeChanged, bufferIncData
                                                .position()
                                                - indexLastTimeChanged,
                                            receivingBodyData);
                                        indexLastTimeChanged =
                                            bufferIncData.position();
                                        receivingBodyData = false;
                                        parserState = 0;
                                    }
                                    else
                                    {
                                        rewindOnePosition(bufferIncData);
                                        parserState = 0;
                                    }
                                }
                            }

                            else if ((incomingTransaction.getTID().length() > (parserState - 9))
                                && bufferIncData.get() == incomingTransaction
                                    .getTID().charAt(parserState - 9))
                            {
                                parserState++;
                            }
                            else
                            {

                                rewindOnePosition(bufferIncData);
                                parserState = 0;
                            }
                        }// end of default:
                        break;
                    }

                }// else from if(!receivingBinaryData)
            }// while (bufferIncData.hasRemaining())

            /*
             * we pre processed everything, unless we are in binary mode and in
             * a state different than zero we should process all the remaining
             * data
             */
            if (receivingBodyData && parserState != 0)
            {
                /* here we append the remaining data and process the rest */
                int offset =
                    (bufferIncData.position() - parserState)
                        - indexLastTimeChanged;
                // try added to catch an overflow bug! Issue #
                try
                {
                    smallBuffer.put(bufferIncData.array(), offset,
                        bufferIncData.position() - offset);
                }
                catch (BufferOverflowException e)
                {
                    logger.error("PreParser smallBuffer Overflow, trying to "
                        + "put: " + (bufferIncData.position() - offset)
                        + " bytes, offset: " + offset + " bufferIncData "
                        + "position:" + bufferIncData.position()
                        + " smallbuffer content:"
                        + new String(bufferIncData.array(), TextUtils.utf8)
                        		.substring(offset, bufferIncData.position())
                        + "|end of content excluding the | char");
                    throw e;
                }
                parser(bufferIncData.array(), indexLastTimeChanged, offset,
                    receivingBodyData);
            }
            else
            {
                parser(bufferIncData.array(), indexLastTimeChanged,
                    bufferIncData.position() - indexLastTimeChanged,
                    receivingBodyData);
            }
        }

        /**
         * Rewinds one position in the given buffer, if possible, i.e. if it's
         * at the beginning it will not rewind
         * 
         * @param buffer the buffer to rewind
         */
        private void rewindOnePosition(ByteBuffer buffer)
        {
            int position = buffer.position();
            if (position == 0)
                return;
            buffer.position(position - 1);
        }
    }// class PreParser

    private boolean receivingTransaction = false;

    private Transaction incomingTransaction = null;

    private String remainderReceive = new String();

    /* Find the MSRP start of the transaction stamp

     * the TID has to be at least 64bits long = 8chars
     * given as a reasonable limit of 20 for the transaction id
     * although non normative. Also the method name will have the same 20 limit
     *  and has to be a Upper case word like SEND
     */
    private static Pattern startRequest = Pattern.compile(
                    "(^MSRP) ([\\p{Alnum}]{8,20}) ([\\p{Upper}]{1,20})\r\n(.*)",
                    Pattern.DOTALL);
    private static Pattern startResponse = Pattern.compile(
                    "(^MSRP) ([\\p{Alnum}]{8,20}) ((\\d{3})([^\r\n]*)\r\n)(.*)",
                    Pattern.DOTALL);

    /**
     * Parse the incoming data, identifying transaction start or end,
     * creating a new transaction according RFC.
     * 
     * @param incomingBytes raw byte data to be handled
     * @param offset the starting position in the given byte array we should
     *            consider for processing
     * @param length the number of bytes to process starting from the offset
     *            position
     * @param receivingBodyData true if it is receiving data regarding the body
     *            of a transaction, false otherwise
     * @throws ConnectionParserException Generic parsing exception
     */
    private void parser(byte[] incomingBytes, int offset, int length,
        boolean receivingBodyData) throws ConnectionParserException
    {
        /*
         * TODO/Cleanup With the introduction of the preparser this method could
         * probably be cleaned up because there are actions that make no sense
         * anymore
         */
        if (receivingBodyData)
        {
            try
            {
                incomingTransaction.parse(incomingBytes, offset, length,
                    receivingBodyData);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        else
        {
            /*
             * here it means that we are receiving text and will have the same
             * behavior than before we made the distinction between binary and
             * text data
             */
            String incomingString =
                new String(incomingBytes, offset, length, TextUtils.utf8);
            String toParse;
            String tID;
            boolean complete = false;
            /*
             * this checks if we have any data reaming to parse from the
             * previous calls to this function, if so it appends it to the start
             * of the incoming data
             */
            if (remainderReceive.length() > 0)
            {
                toParse = remainderReceive.concat(incomingString);
                remainderReceive = new String();
            }
            else
            {
                toParse = incomingString;
            }

            /*
             * Variable used in case a call to this method is done with
             * more than one transaction in the incomingString
             */
            ArrayList<String> txRest = new ArrayList<String>();
            while (!complete
                && (toParse.length() >= 10 || txRest.size() != 0))
            {
                /*
                 * Transaction trim mechanism: (used to deal with the receipt of
                 * more than one transaction on the incomingString) we join
                 * every transaction back to the toParse string for it to be
                 * dealt with
                 */
                if (txRest.size() > 0)
                {
                    toParse = toParse.concat(txRest.get(0));
                    txRest.remove(0);
                }
                if (txRest.size() > 1)
                    throw new RuntimeException(
                    		"restTransactions were never meant "
                            + "to have more than one element!");
                /* end Transaction trim mechanism. */

                if (!receivingTransaction)
                {
                    Matcher matchRequest = startRequest.matcher(toParse);
                    Matcher matchResponse = startResponse.matcher(toParse);

                    if (matchRequest.matches())
                    {
                        // Retrieve TID and create new transaction
                        receivingTransaction = true;
                        tID = matchRequest.group(2);
                        toParse = matchRequest.group(4);
                        String type = matchRequest.group(3).toUpperCase();
                        TransactionType newType;
                        try
                        {
                            newType = TransactionType.valueOf(type);
                            logger.debug("Tx-" + newType + "[" + tID + "]");
                        }
                        catch (IllegalArgumentException iae)
                        {
                            newType = TransactionType.UNSUPPORTED;
                            logger.warn("Unsupported transaction type: Tx-"
                        			+ type + "[" + tID + "]");
                        }
                        try
                        {
                            incomingTransaction = new Transaction(tID, newType,
                            				transactionManager, Transaction.IN);
                        }
                        catch (IllegalUseException e)
                        {
                            logger.error("Cannot create an incoming transaction", e);
                        }
                        if (newType == TransactionType.UNSUPPORTED)
                        {
                            incomingTransaction.signalizeEnd('$');
                            logger
                                .warn("Found an unsupported transaction type for["
                                    + tID
                                    + "] signalised end and called update");
                            setChanged();
                            notifyObservers(newType);
                        }
                        complete = false;

                    }
                    else if (matchResponse.matches())
                    {
                        receivingTransaction = true;
                        tID = matchResponse.group(2);
                        int status = Integer.parseInt(matchResponse.group(4));
                        String comment = matchResponse.group(5);
                        if (matchResponse.group(6) != null)
                            toParse = matchResponse.group(6);

                        incomingTransaction =
                            transactionManager.getTransaction(tID);
                        if (incomingTransaction == null)
                        {
                            logger.error("Received response for unknown transaction");
                        }
                        // Has to encounter the end of the transaction as well
                        // (not sure this is true)

                        logger.debug("Found response to transaction: " + tID);
                        try
                        {
                            Transaction trResponse =
                                new TransactionResponse(incomingTransaction,
                                			status, comment, Transaction.IN);
                            incomingTransaction = trResponse;
                        }
                        catch (IllegalUseException e)
                        {
                            throw new ConnectionParserException(
                                "Cannot create transaction response", e);
                        }
                    }
                    else
                    {
                        logger.error("Start of transaction not found while parsing: "
                                + toParse.substring(0, toParse.length() > 80 ? 80 : toParse.length()));
                        throw new ConnectionParserException(
                            "Error, start of the transaction not found on thread: "
                            + Thread.currentThread().getName());
                    }
                }
                if (receivingTransaction)
                {
                    /*
                     * variable used to store the start of data position in
                     * order to optmize the data to save mechanism below
                     */
                    int startOfDataMark = 0;
                    /*
                     * Transaction trim mechanism: Search for the end of the
                     * transaction and trim the toParse string to contain only
                     * one transaction and add the rest to the restTransactions
                     */
                    Pattern endTransaction;
                    tID = incomingTransaction.getTID();
                    endTransaction =
                        Pattern.compile(
                        		"(.*)(-------" + tID + ")([$+#])(\r\n)(.*)?",
                        		Pattern.DOTALL);
                    Matcher matchEndTransaction =
                        endTransaction.matcher(toParse);
                    if (matchEndTransaction.matches())
                    {
                        logger.trace("found end of transaction: " + tID);
                        toParse = matchEndTransaction.group(1)
                                + matchEndTransaction.group(2)
                                + matchEndTransaction.group(3)
                                + matchEndTransaction.group(4);
                        /*
                         * add any remaining data to restTransactions
                         */
                        if (matchEndTransaction.group(5) != null &&
                    		!matchEndTransaction.group(5).equalsIgnoreCase( ""))
                            txRest.add(matchEndTransaction.group(5));
                    }
                    /*
                     * End of transaction trim mechanism
                     */

                    // Identify the end of the transaction (however it might not
                    // exist yet or it may not be complete):
                    /*
                     * identify if this transaction has content-stuff or not by
                     * the 'Content-Type 2CRLF' on the formal syntax
                     */
                    // TODO still the Content-type pattern isn't entirely
                    // correct, still have to account for the fact that the
                    // subtype * (might not exist!) eventually pass this to the
                    // RegexMSRPFactory
                    String tokenRegex = RegexMSRPFactory.token.pattern();
                    Pattern contentStuff =
                        Pattern.compile("(.*)(Content-Type:) (" + tokenRegex
                            + "/" + tokenRegex + ")(\r\n\r\n)(.*)",
                            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
                    Matcher matchContentStuff = contentStuff.matcher(toParse);
                    if (matchContentStuff.matches())
                    {
                        logger.trace("transaction " + tID
                            + " was found to have contentstuff");
                        incomingTransaction.hasContentStuff = true;
                        startOfDataMark = matchContentStuff.end(4);
                    }
                    // note if this is a response the hasContentStuff is set to
                    // false on the gotResponse method, so no need to set it
                    // here
                    // although it should be here for legibility reasons
                    if (incomingTransaction.hasContentStuff)
                        endTransaction =
                            Pattern.compile("(.*)(\r\n)(-------" + tID
                                + ")([$+#])(\r\n)(.*)?", Pattern.DOTALL);
                    else
                        endTransaction =
                            Pattern.compile("(.*)(-------" + tID
                                + ")([$+#])(\r\n)(.*)?", Pattern.DOTALL);
                    matchEndTransaction = endTransaction.matcher(toParse);
                    // DEBUG -start here- REMOVE
                    if (matchEndTransaction.matches())
                    {
                        /*
                         * log all of the parts of the regex match:
                         */
                        String endTransactionLogDetails =
                            "Found the end of transaction tID:" + tID
                                + " details: ";
                        endTransactionLogDetails =
                            endTransactionLogDetails
                                .concat("pre-end-line body: "
                                    + matchEndTransaction.group(1)
                                    + " end of line withouth C.F.:");
                        int aux = 2;
                        if (incomingTransaction.hasContentStuff)
                            aux = 3;
                        endTransactionLogDetails =
                            endTransactionLogDetails.concat(matchEndTransaction
                                .group(aux++)
                                + " C.F.: "
                                + matchEndTransaction.group(aux++)
                                + " rest of the message: "
                                + matchEndTransaction.group(aux++));
                        logger.trace(endTransactionLogDetails);
                    }
                    // DEBUG -end here- REMOVE

                    int i = 0;
                    // if we have a complete end of transaction:
                    if (matchEndTransaction.matches())
                    {
                        String restData;
                        try
                        {
                            incomingTransaction.parse(matchEndTransaction
                                .group(1).getBytes(TextUtils.utf8), 0,
                                matchEndTransaction.group(1).length(),
                                receivingBodyData);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                        if (incomingTransaction.hasContentStuff)
                        {
                            incomingTransaction
                                .signalizeEnd(matchEndTransaction.group(4)
                                    .charAt(0));
                            restData = matchEndTransaction.group(6);
                        }
                        else
                        {
                            incomingTransaction
                                .signalizeEnd(matchEndTransaction.group(3)
                                    .charAt(0));
                            restData = matchEndTransaction.group(5);
                        }
                        setChanged();
                        notifyObservers(incomingTransaction);
                        receivingTransaction = false;
                        // parse the rest of the received data extracting the
                        // already parsed parts
                        if (restData != null && !restData.equalsIgnoreCase(""))
                        {
                            toParse = restData;
                            complete = false;
                        }
                        else
                        {
                            if (restData != null)
                                toParse = restData;
                            if (txRest.size() == 0)
                                complete = true;
                        }
                    }
                    else
                    {
                        // we trim the toParse so that we don't abruptly cut an
                        // end of transaction we save the characters that
                    	// we trimmed to be analyzed next
                        int j;
                        char[] toSave;
                        // we get the possible beginning of the trim characters
                        i = toParse.lastIndexOf('\r');

                        /*
                         * Performance optimizer: if we have a marker that has
                         * the position of the data start and the last position
                         * of '\r' identified is in the headers then don't save
                         * anything
                         */
                        if (startOfDataMark != 0 && startOfDataMark != -1)
                            if (i < startOfDataMark)
                                i = -1;

                        for (j = 0, toSave = new char[toParse.length() - i];
                        		i < toParse.length(); i++, j++)
                        {
                            if (i == -1 || i == toParse.length())
                                break;

                            toSave[j] = toParse.charAt(i);
                        }

                        // buildup of the regex pattern of the possible end of
                        // transaction characters
                        String patternStringEndT =
                            new String(
                                "((\r\n)|(\r\n-)|(\r\n--)|(\r\n---)|(\r\n----)|(\r\n-----)|(\r\n------)|"
                                    + "(\r\n-------)");
                        CharBuffer tidBuffer = CharBuffer.wrap(tID);
                        for (i = 0; i < tID.length(); i++)
                        {
                            patternStringEndT =
                                patternStringEndT.concat("|(\r\n-------"
                                    + tidBuffer.subSequence(0, i) + ")");
                        }
                        patternStringEndT = patternStringEndT.concat(")?$");

                        Pattern endTransactionTrim =
                            Pattern.compile(patternStringEndT, Pattern.DOTALL);
                        String toSaveString = new String(toSave);

                        // toSaveString = "\n--r";
                        matchEndTransaction =
                            endTransactionTrim.matcher(toSaveString);

                        if (matchEndTransaction.matches())
                        {
                            // if we indeed have end of transaction characters
                            // in the end of the data we add them to the
                        	// string that will be analyzed next
                            remainderReceive =
                                remainderReceive.concat(toSaveString);

                            logger.trace("trimming end of transaction, before: "
                                    	+ toParse);
                            // trimming of the data to parse
                            toParse =
                                toParse.substring(0, toParse.lastIndexOf('\r'));
                            logger.trace("trimming end of transaction, after: "
                            			+ toParse);
                        }
                        try
                        {
                            incomingTransaction.parse(
                                toParse.getBytes(TextUtils.utf8), 0, toParse.length(),
                                receivingBodyData);
                        }
                        catch (Exception e)
                        {
                            logger.error(
	                            "Got an exception while parsing data to a transaction",
	                            e);
                        }
                        if (txRest.size() == 0)
                            complete = true;
                    }
                }
            }
        }
    }

    private ThreadGroup ioOperationGroup;

    public void addEndPoint(URI uri, InetAddress address) throws IOException
    {
        // Adds the given endpoint to the socket address and starts the
        // listening/writing cycle
        SocketAddress remoteAddress =
            new InetSocketAddress(uri.getHost(), uri.getPort());
        /*
         * TODO FIXME probably the new TransactionManager isn't needed, however
         * i'll still create it but copy the values needed for automatic testing
         * SubIssue #1
         */
        boolean testingOld = false;
        String presetTidOld = new String();
        // -- start of the code that enables a transaction test.
        if (transactionManager != null)
        {
            testingOld = transactionManager.testing;
            presetTidOld = transactionManager.presetTID;

        }
        transactionManager = new TransactionManager(this);
        transactionManager.testing = testingOld;
        transactionManager.presetTID = presetTidOld;
        // -- end of the code that enables a transaction test.

        socketChannel.connect(remoteAddress);
        Connections connectionsInstance =
            MSRPStack.getConnectionsInstance(address);

        ioOperationGroup =
            new ThreadGroup(connectionsInstance.getConnectionsGroup(),
                "IO OP connection " + uri.toString() + " group");
        connectionsInstance.startConnectionThread(this, ioOperationGroup);
    }

    /**
     * @return the InetAddress of the locally bound IP
     */
    public InetAddress getLocalAddress()
    {
        return socketChannel.socket().getLocalAddress();
    }

    /**
     * Method used to notify the write cycle thread
     */
    public void notifyWriteThread()
    {
        if (writeThread != null)
        {
            synchronized (writeThread)
            {
                writeThread.notify();
            }
        }
    }
}
