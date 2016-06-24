package client;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.StringTokenizer;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;

public class Client {

	// GUI elements
	JFrame f = new JFrame("Client");
	JButton setupButton = new JButton("Setup");
	JButton playButton = new JButton("Play");
	JButton pauseButton = new JButton("Pause");
	JButton tearButton = new JButton("Teardown");
	JPanel mainPanel = new JPanel();
	JPanel buttonPanel = new JPanel();
	JLabel iconLabel = new JLabel();
	ImageIcon icon;

	// RTP variables:
	DatagramPacket rcvdp; // UDP packet received from the server
	DatagramSocket RTPsocket; // socket to be used to send and receive UDP packets
	static int RTP_RCV_PORT = 9001; // port where the client will receive the RTP packets

	Timer timer; // timer used to receive data from the UDP socket
	byte[] buf; // buffer used to store data received from the server

	// RTSP variables
	// RTSP States
	final static int INIT = 0;
	final static int READY = 1;
	final static int PLAYING = 2;
	static int state; // RTSP state == INIT or READY or PLAYING
	Socket RTSPsocket; // socket used to send/receive RTSP messages
	// Input and output stream filters
	static BufferedReader RTSPBufferedReader;
	static BufferedWriter RTSPBufferedWriter;
	static String VideoFileName; // video file to request to the server
	int RTSPSeqNb = 0; // Sequence number of RTSP messages within the session
	int RTSPid = 0; // ID of the RTSP session (given by the RTSP Server)

	final static String CRLF = "\r\n"; // To end header lines

	// Video constants:
	static int MJPEG_TYPE = 26; // RTP payload type for MJPEG video

	/**
	 * The constructor of the client which will build the GUI elements
	 */
	public Client() {

		// Frame
		f.addWindowListener(new WindowAdapter() {
			public void windowClosing(WindowEvent e) {
				System.exit(0);
			}
		});

		// Buttons
		buttonPanel.setLayout(new GridLayout(1, 0));
		buttonPanel.add(setupButton);
		buttonPanel.add(playButton);
		buttonPanel.add(pauseButton);
		buttonPanel.add(tearButton);
		setupButton.addActionListener(new setupButtonListener());
		playButton.addActionListener(new playButtonListener());
		pauseButton.addActionListener(new pauseButtonListener());
		tearButton.addActionListener(new tearButtonListener());

		// Image display label
		iconLabel.setIcon(null);

		// frame layout
		mainPanel.setLayout(null);
		mainPanel.add(iconLabel);
		mainPanel.add(buttonPanel);
		iconLabel.setBounds(0, 0, 380, 280);
		buttonPanel.setBounds(0, 280, 380, 50);

		f.getContentPane().add(mainPanel, BorderLayout.CENTER);
		f.setSize(new Dimension(390, 370));
		f.setVisible(true);

		// Initialize the timer with a delay of 20ms
		timer = new Timer(20, new timerListener());
		timer.setInitialDelay(0); //The delay after the timer starts for the first time
		timer.setCoalesce(true); //Avoid that many events will queue after each other

		// Allocate enough memory for the buffer used to receive data from the server
		buf = new byte[15000];
	}

	/*
	 * The main method which will be invoked by the command line
	 */
	public static void main(String argv[]) throws Exception {
		// Create a Client object
		Client theClient = new Client();

		// Get server RTSP port and IP address from the command line
		int RTSP_server_port = 8554; // Integer.parseInt(argv[1]);
		String ServerHost = "192.168.0.102"; // argv[0];
		InetAddress ServerIPAddr = InetAddress.getByName(ServerHost);

		// Get video filename to request
		VideoFileName = "movie.Mjpeg"; // argv[2];

		// Establish a TCP connection with the server to exchange RTSP messages
		theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);
		System.out.println("CLIENT CREATED A RTSP SOCKET WITH PORT NUMBER: " + theClient.RTSPsocket.getPort());

		// Set input and output stream filters:
		RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
		RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

		// Initialize RTSP state:
		state = INIT;
	}

	/*
	 * This class will handle the SETUP button click via RTSP
	 * Will setup a RTP connection with the server
	 */
	class setupButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Setup Button pressed!");
			if (state == INIT) {
				// Initialize non-blocking RTPsocket that will be used to receive data
				try {
					// Construct a new DatagramSocket (UDP) to receive RTP packets
					RTPsocket = new DatagramSocket(RTP_RCV_PORT); // Setting up port for the client himself on port number 25000
					System.out.println("CLIENT CREATED A RTP SOCKET WITH PORT NUMBER: " + RTPsocket.getPort());
					
					//If no data arrives within 5ms a SocketException will be thrown
					RTPsocket.setSoTimeout(5);
				} catch (SocketException se) {
					System.out.println("Socket exception: " + se);
					System.exit(0);
				}

				// Initialize RTSP sequence number
				RTSPSeqNb = 1;

				// Send SETUP message to the server
				send_RTSP_request("SETUP");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// Change RTSP state and print new state
					state = READY;
					System.out.println("New RTSP state: READY");
				}
			} // else if state != INIT then do nothing
		}
	}

	/*
	 * This class will handle the PLAY button click via RTSP
	 */
	class playButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Play Button pressed !");

			if (state == READY) {

				// Increase RTSP sequence number
				RTSPSeqNb++;

				// Send PLAY message to the server
				send_RTSP_request("PLAY");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// Change RTSP state and print out new state
					state = PLAYING;
					System.out.println("New RTSP state: PLAYING");

					// Start the timer and sending action events to listeners
					timer.start();
				}
			} // else if state != READY then do nothing
		}
	}

	/*
	 * This class will handle clicking on the pause button
	 */
	class pauseButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Pause Button pressed !");

			if (state == PLAYING) {
				RTSPSeqNb++;

				// Send PAUSE message to the server
				send_RTSP_request("PAUSE");

				// Wait for the response
				if (parse_server_response() != 200)
					System.out.println("Invalid Server Response");
				else {
					// Change RTSP state and print out new state
					state = READY;
					System.out.println("New RTSP state: ...");

					// Stop the timer
					timer.stop();
				}
			}
			// else if state != PLAYING then do nothing
		}
	}

	/*
	 * This class will handle clicking on the tear down button
	 */
	class tearButtonListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			System.out.println("Teardown Button pressed !");
			RTSPSeqNb++;

			// Send TEARDOWN message to the server
			send_RTSP_request("TEARDOWN");

			// Wait for the response
			if (parse_server_response() != 200)
				System.out.println("Invalid Server Response");
			else {
				// change RTSP state and print out new state
				state = INIT;
				System.out.println("New RTSP state: ...");

				// Stop the timer
				timer.stop();

				// Exit the system
				System.exit(0);
			}
		}
	}

	/*
	 * The actionPerformed method will be invoked by the timer
	 * continuously until the timer stops.
	 */
	class timerListener implements ActionListener {
		public void actionPerformed(ActionEvent e) {

			// Construct a DatagramPacket to receive data from the UDP socket
			// The data will be put in the buffer (buf) which has a length of buf.lenght
			rcvdp = new DatagramPacket(buf, buf.length);

			try {
				// Receive the DataPacket from the socket with the video data from the socket
				RTPsocket.receive(rcvdp);

				// Create an RTPpacket object from the DataPacket
				RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());

				// Print important header fields of the RTP packet received:
				System.out.println("Got RTP packet with SeqNum # " + rtp_packet.getsequencenumber() + " TimeStamp "
						+ rtp_packet.gettimestamp() + " ms, of type " + rtp_packet.getpayloadtype());

				// Print header bit stream:
				rtp_packet.printheader();

				// Get the payload bitstream from the RTPpacket object
				int payload_length = rtp_packet.getpayload_length(); // Will be 26 in this case because the type was 26: MJPEG
				System.out.println("Payload is: " + payload_length);
				byte[] payload = new byte[payload_length];
				rtp_packet.getpayload(payload);

				// Get an Image object from the payload bitstream
				Toolkit toolkit = Toolkit.getDefaultToolkit();
				Image image = toolkit.createImage(payload, 0, payload_length);

				// Display the image as an ImageIcon object
				icon = new ImageIcon(image);
				iconLabel.setIcon(icon);
			} catch (InterruptedIOException iioe) {
				System.out.println("Exception caught: " + iioe);
			} catch (IOException ioe) {
				System.out.println("Exception caught: " + ioe);
			}
		}
	}

	/*
	 * This method will simply read the server response and
	 * will filter the status code out of it. For example 200 for OK
	 * and 400 for error and will return this status code
	 */
	private int parse_server_response() {
		int reply_code = 0;

		try {
			// Read the status line which is the first line in the header reponse from the server.
			// So in this case this first line could be: RTSP/1.0 200 OK. When you call Read line again,
			// It will move to the second line and so on.
			String StatusLine = RTSPBufferedReader.readLine(); 
			System.out.println(StatusLine);

			// The tokenizer will split a string based on spaces between the words
			StringTokenizer tokens = new StringTokenizer(StatusLine);
			tokens.nextToken(); // Skip over the RTSP version (RTSP/1.0)
			reply_code = Integer.parseInt(tokens.nextToken()); //Get the code which is 200 (so the OK is avoided)

			// If reply code is OK get and print the 2 other lines
			if (reply_code == 200) {
				// Now read the second line, which could be: CSeq: 1 for example.
				String SeqNumLine = RTSPBufferedReader.readLine(); 
				System.out.println(SeqNumLine);

				// Read the third line which could be: Session: 123456 for example.
				String SessionLine = RTSPBufferedReader.readLine();
				System.out.println(SessionLine);

				// If state == INIT gets the Session Id from the SessionLine
				tokens = new StringTokenizer(SessionLine);
				tokens.nextToken(); // Skip over the Session:
				RTSPid = Integer.parseInt(tokens.nextToken()); //Will be 123456 in this case
			}
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
			System.exit(0);
		}

		return (reply_code);
	}

	/*
	 * This method will send a RTSP request to the server
	 */
	private void send_RTSP_request(String request_type) {
		try {
			
			RTSPBufferedWriter.write(request_type + " " + VideoFileName + " " + "RTSP/1.0" + CRLF);
			RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

			if (request_type.equals("SETUP")) {
				RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
			} else {
				RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
			}

			// Flushes the output stream and forces any buffered output bytes to be written out 
			RTSPBufferedWriter.flush();
		} catch (Exception ex) {
			System.out.println("Exception caught: " + ex);
		}
	}

}// end of Class Client
