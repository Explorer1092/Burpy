package burp;

import burp.ui.MessageDialog;
import net.razorvine.pyro.PyroException;
import net.razorvine.pyro.PyroProxy;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class BurpExtender implements IBurpExtender, ITab, ActionListener, IContextMenuFactory, MouseListener, IExtensionStateListener, IIntruderPayloadProcessor,IHttpListener,IMessageEditorTabFactory {

	private IBurpExtenderCallbacks callbacks;
	private IExtensionHelpers helpers;

	private PrintWriter stdout;
	private PrintWriter stderr;

	private JPanel mainPanel;

	public PyroProxy pyroBurpyService;
	private Process pyroServerProcess;

	private JTextField pythonPath;
	private String pythonScript;
	private JTextField pyroHost;
	private JTextField pyroPort;
	private JTextPane serverStatus;

	private JTextField burpyPath;

	private JCheckBox chckbxPro;
	private JCheckBox chckbxAuto;

	public Boolean should_pro = false;
	public Boolean should_auto = false;


	private Style redStyle;
	private Style greenStyle;
	DefaultStyledDocument documentServerStatus;

	DefaultStyledDocument documentServerStatusButtons;
	DefaultStyledDocument documentApplicationStatusButtons;
	private JTextPane serverStatusButtons;


	private boolean serverStarted;

	private IContextMenuInvocation currentInvocation;

	private JButton clearConsoleButton;
	private JButton reloadScript;

	private JEditorPane pluginConsoleTextArea;

	private Thread stdoutThread;
	private Thread stderrThread;




	private boolean lastPrintIsJS;

	public List<String> burpyMethods;
	public String serviceHost;
	public int servicePort;
	public String serviceObj="BurpyServicePyro";

	@Override
	public void registerExtenderCallbacks(IBurpExtenderCallbacks c) {

		// Keep a reference to our callbacks object
		this.callbacks = c;

		// Obtain an extension helpers object
		helpers = callbacks.getHelpers();

		// Set our extension name
		callbacks.setExtensionName("Burpy");

		// register ourselves as an Intruder payload processor
		callbacks.registerIntruderPayloadProcessor(this);


		//register to produce options for the context menu
		callbacks.registerContextMenuFactory(this);

		// register to execute actions on unload
		callbacks.registerExtensionStateListener(this);

		// register editor tab
		callbacks.registerMessageEditorTabFactory(this);

		// Initialize stdout and stderr
		stdout = new PrintWriter(callbacks.getStdout(), true);
		stderr = new PrintWriter(callbacks.getStderr(), true);

		stdout.println("Github: https://github.com/mr-m0nst3r/Burpy");
		stdout.println("Website: https://m0nst3r.me");
		stdout.println("");

		serverStarted = false;

		lastPrintIsJS = false;

		try {
			InputStream inputStream = getClass().getClassLoader().getResourceAsStream("res/burpyServicePyro.py");
			BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream ));
			File outputFile = new File(System.getProperty("java.io.tmpdir") + System.getProperty("file.separator") + "burpyServicePyro.py");

			FileWriter fr = new FileWriter(outputFile);
			BufferedWriter br  = new BufferedWriter(fr);

			String s;
			while ((s = reader.readLine())!=null) {

				br.write(s);
				br.newLine();

			}
			reader.close();
			br.close();

			pythonScript = outputFile.getAbsolutePath();

		} catch(Exception e) {

			printException(e,"Error copying Pyro Server file");

		}

		SwingUtilities.invokeLater(new Runnable()  {

			@Override
			public void run()  {

				mainPanel = new JPanel();
				mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));

				JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

				// **** Left panel (tabbed plus console)
				JSplitPane consoleTabbedSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);

				// Tabbed Pabel
				final JTabbedPane tabbedPanel = new JTabbedPane();

				// **** TABS

				// **** CONFIGURATION PANEL

				JPanel configurationConfPanel = new JPanel();
				configurationConfPanel.setLayout(new BoxLayout(configurationConfPanel, BoxLayout.Y_AXIS));

				// RED STYLE
				StyleContext styleContext = new StyleContext();
				redStyle = styleContext.addStyle("red", null);
				StyleConstants.setForeground(redStyle, Color.RED);
				// GREEN STYLE
				greenStyle = styleContext.addStyle("green", null);
				StyleConstants.setForeground(greenStyle, Color.GREEN);

				JPanel serverStatusPanel = new JPanel();
				serverStatusPanel.setLayout(new BoxLayout(serverStatusPanel, BoxLayout.X_AXIS));
				serverStatusPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel labelServerStatus = new JLabel("Server status: ");
				documentServerStatus = new DefaultStyledDocument();
				serverStatus = new JTextPane(documentServerStatus);
				try {
					documentServerStatus.insertString(0, "NOT running", redStyle);
				} catch (BadLocationException e) {
					printException(e,"Error setting labels");
				}

				serverStatus.setMaximumSize( serverStatus.getPreferredSize() );
				serverStatusPanel.add(labelServerStatus);
				serverStatusPanel.add(serverStatus);


				JPanel pythonPathPanel = new JPanel();
				pythonPathPanel.setLayout(new BoxLayout(pythonPathPanel, BoxLayout.X_AXIS));
				pythonPathPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel labelPythonPath = new JLabel("Python binary path: ");
				pythonPath = new JTextField(200);
				if(callbacks.loadExtensionSetting("pythonPath") != null)
					pythonPath.setText(callbacks.loadExtensionSetting("pythonPath"));
				else {
					if(System.getProperty("os.name").startsWith("Windows")) {
						pythonPath.setText("C:\\python27\\python");
					} else {
						pythonPath.setText("/usr/bin/python");
					}
				}
				pythonPath.setMaximumSize( pythonPath.getPreferredSize() );
				JButton pythonPathButton = new JButton("Select file");
				pythonPathButton.setActionCommand("pythonPathSelectFile");
				pythonPathButton.addActionListener(BurpExtender.this);
				pythonPathPanel.add(labelPythonPath);
				pythonPathPanel.add(pythonPath);
				pythonPathPanel.add(pythonPathButton);

				JPanel pyroHostPanel = new JPanel();
				pyroHostPanel.setLayout(new BoxLayout(pyroHostPanel, BoxLayout.X_AXIS));
				pyroHostPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel labelPyroHost = new JLabel("Pyro host: ");
				pyroHost = new JTextField(200);
				if(callbacks.loadExtensionSetting("pyroHost") != null)
					pyroHost.setText(callbacks.loadExtensionSetting("pyroHost"));
				else
					pyroHost.setText("127.0.0.1");
				pyroHost.setMaximumSize( pyroHost.getPreferredSize() );
				pyroHostPanel.add(labelPyroHost);
				pyroHostPanel.add(pyroHost);

				JPanel burpyPathPanel = new JPanel();
				burpyPathPanel.setLayout(new BoxLayout(burpyPathPanel, BoxLayout.X_AXIS));
				burpyPathPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel labelBurpyPath = new JLabel("Burpy PY file path: ");
				burpyPath = new JTextField(200);
				if(callbacks.loadExtensionSetting("burpyPath") != null)
					burpyPath.setText(callbacks.loadExtensionSetting("burpyPath"));
				else {
					if(System.getProperty("os.name").startsWith("Windows")) {
						burpyPath.setText("C:\\burp\\script.py");
					} else {
						burpyPath.setText("/home/m0nst3r/work/scripts/jnBank.py");
					}
				}
				burpyPath.setMaximumSize( burpyPath.getPreferredSize() );
				JButton burpyPathButton = new JButton("Select file");
				burpyPathButton.setActionCommand("burpyPathSelectFile");
				burpyPathButton.addActionListener(BurpExtender.this);
//                JButton fridaDefaultPathButton = new JButton("Load default PY file");
//                fridaDefaultPathButton.setActionCommand("burpyPathSelectDefaultFile");
//                fridaDefaultPathButton.addActionListener(BurpExtender.this);
				burpyPathPanel.add(labelBurpyPath);
				burpyPathPanel.add(burpyPath);
				burpyPathPanel.add(burpyPathButton);

				JPanel pyroPortPanel = new JPanel();
				pyroPortPanel.setLayout(new BoxLayout(pyroPortPanel, BoxLayout.X_AXIS));
				pyroPortPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
				JLabel labelPyroPort = new JLabel("Pyro port: ");
				pyroPort = new JTextField(200);
				if(callbacks.loadExtensionSetting("pyroPort") != null)
					pyroPort.setText(callbacks.loadExtensionSetting("pyroPort"));
				else
					pyroPort.setText("19999");
				pyroPort.setMaximumSize( pyroPort.getPreferredSize() );
				pyroPortPanel.add(labelPyroPort);
				pyroPortPanel.add(pyroPort);

				
				chckbxPro = new JCheckBox("Enable Processor (require processor function)");
				chckbxPro.setEnabled(true);
				chckbxPro.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent e) {
						if (chckbxPro.isSelected()){
							should_pro = true;
						}else {
							should_pro = false;
						}
					}
				});

				chckbxAuto = new JCheckBox("Enable Auto Enc/Dec (require encrypt and decrypt function)");
				chckbxAuto.setEnabled(true);
				chckbxAuto.addActionListener(new ActionListener() {
					@Override
					public void actionPerformed(ActionEvent actionEvent) {
						if (chckbxAuto.isSelected()){
							should_auto = true;
						} else {
							should_auto = false;
						}
					}
				});


				GridBagConstraints autoSignBox = new GridBagConstraints();
				autoSignBox.fill = GridBagConstraints.HORIZONTAL;
				autoSignBox.insets = new Insets(0, 0, 5, 0);
				autoSignBox.gridx = 1;
				autoSignBox.gridy = 3;


				GridBagConstraints shouldProBox = new GridBagConstraints();
				shouldProBox.fill = GridBagConstraints.HORIZONTAL;
				shouldProBox.insets = new Insets(0, 0, 5, 0);
				shouldProBox.gridx = 1;
				shouldProBox.gridy = 3;

				GridBagConstraints shouldAutoBox = new GridBagConstraints();
				shouldAutoBox.fill = GridBagConstraints.HORIZONTAL;
				shouldAutoBox.insets = new Insets(0, 0, 5, 0);
				shouldAutoBox.gridx = 1;
				shouldAutoBox.gridy = 3;


				configurationConfPanel.add(serverStatusPanel);
				configurationConfPanel.add(pythonPathPanel);
				configurationConfPanel.add(pyroHostPanel);
				configurationConfPanel.add(pyroPortPanel);
				configurationConfPanel.add(burpyPathPanel);

				configurationConfPanel.add(chckbxPro,shouldProBox);
				configurationConfPanel.add(chckbxAuto, shouldAutoBox);

				// **** END CONFIGURATION PANEL


				tabbedPanel.add("Configurations",configurationConfPanel);

				// *** CONSOLE
				pluginConsoleTextArea = new JEditorPane("text/html", "<font color=\"green\"><b>*** Burpy Console ***</b></font><br/><br/>");
				JScrollPane scrollPluginConsoleTextArea = new JScrollPane(pluginConsoleTextArea);
				scrollPluginConsoleTextArea.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
				pluginConsoleTextArea.setEditable(false);

				consoleTabbedSplitPane.setTopComponent(tabbedPanel);
				consoleTabbedSplitPane.setBottomComponent(scrollPluginConsoleTextArea);
				consoleTabbedSplitPane.setResizeWeight(.7d);

				// *** RIGHT - BUTTONS

				// RIGHT
				JPanel rightSplitPane = new JPanel();
				rightSplitPane.setLayout(new GridBagLayout());
				GridBagConstraints gbc = new GridBagConstraints();
				gbc.gridwidth = GridBagConstraints.REMAINDER;
				gbc.fill = GridBagConstraints.HORIZONTAL;

				documentServerStatusButtons = new DefaultStyledDocument();
				serverStatusButtons = new JTextPane(documentServerStatusButtons);
				try {
					documentServerStatusButtons.insertString(0, "Server stopped", redStyle);
				} catch (BadLocationException e) {
					printException(e,"Error setting labels");
				}
				serverStatusButtons.setMaximumSize( serverStatusButtons.getPreferredSize() );

				documentApplicationStatusButtons = new DefaultStyledDocument();

				JButton startServer = new JButton("Start server");
				startServer.setActionCommand("startServer");
				startServer.addActionListener(BurpExtender.this);

				JButton killServer = new JButton("Kill server");
				killServer.setActionCommand("killServer");
				killServer.addActionListener(BurpExtender.this);



				clearConsoleButton = new JButton("Clear console");
				clearConsoleButton.setActionCommand("clearConsole");
				clearConsoleButton.addActionListener(BurpExtender.this);

				reloadScript = new JButton("Reload Script");
				reloadScript.setActionCommand("reloadScript");
				reloadScript.addActionListener(BurpExtender.this);

				JSeparator separator = new JSeparator(SwingConstants.HORIZONTAL);
				separator.setBorder(BorderFactory.createMatteBorder(3, 0, 3, 0, Color.ORANGE));

				rightSplitPane.add(serverStatusButtons,gbc);
				rightSplitPane.add(startServer,gbc);
				rightSplitPane.add(killServer,gbc);


				rightSplitPane.add(clearConsoleButton,gbc);

				rightSplitPane.add(reloadScript,gbc);

				rightSplitPane.add(separator,gbc);


				splitPane.setLeftComponent(consoleTabbedSplitPane);
				splitPane.setRightComponent(rightSplitPane);

				splitPane.setResizeWeight(.9d);

				mainPanel.add(splitPane);

				callbacks.customizeUiComponent(mainPanel);

				callbacks.addSuiteTab(BurpExtender.this);

			}

		});
		callbacks.registerHttpListener(this);

	}
	
	@SuppressWarnings("unchecked")
	public void getMethods() {
			try {
//				service = new PyroProxy(serviceHost, servicePort, serviceObj);
				this.burpyMethods = (List<String>) (pyroBurpyService.call("get_methods"));
//				stdout.println(pyroBurpyService.pyroMethods);
			} catch (Exception e) {
				stderr.println(e.toString());
				StackTraceElement[] exceptionElements = e.getStackTrace();
				for (int i = 0; i < exceptionElements.length; i++) {
					stderr.println(exceptionElements[i].toString());
				}
			}finally {
				if (this.burpyMethods != null) {
					printSuccessMessage("methods loaded");
				}else{
					stdout.println("Methods loading failed");
				}
			}

	}

	private void launchPyroServer(String pythonPath, String pyroServicePath) {

		Runtime rt = Runtime.getRuntime();

		serviceHost = pyroHost.getText().trim();
		servicePort = Integer.parseInt(pyroPort.getText().trim());

		String[] startServerCommand = {pythonPath,"-i",pyroServicePath,serviceHost,Integer.toString(servicePort),burpyPath.getText().trim()};


		try {
			documentServerStatus.insertString(0, "starting up ... ", redStyle);
			pyroServerProcess = rt.exec(startServerCommand);

			final BufferedReader stdOutput = new BufferedReader(new InputStreamReader(pyroServerProcess.getInputStream()));
			final BufferedReader stdError = new BufferedReader(new InputStreamReader(pyroServerProcess.getErrorStream()));

			// Initialize thread that will read stdout
			stdoutThread = new Thread() {

				public void run() {

					while(true) {

						try {

							final String line = stdOutput.readLine();

							// Only used to handle Pyro first message (when server start)
//							if(line.equals("Ready.")) {
							if(line.contains("running") || line.startsWith("Ready.")) {
								pyroBurpyService = new PyroProxy(serviceHost,servicePort,serviceObj);
								serverStarted = true;

								SwingUtilities.invokeLater(new Runnable() {

									@Override
									public void run() {

										serverStatus.setText("");
										serverStatusButtons.setText("");
										try {
											documentServerStatus.insertString(0, "running", greenStyle);
											documentServerStatusButtons.insertString(0, "Server running", greenStyle);
										} catch (BadLocationException e) {

											printException(e,"Exception setting labels");

										}

									}
								});

								printSuccessMessage("Pyro server started correctly");
								printSuccessMessage("Better use \"Kill Server\" after finished!");
								
								printSuccessMessage("Analyzing scripts");
								getMethods();
								// Standard line
							} else {

								printJSMessage(line);

							}


						} catch (IOException e) {
							printException(e,"Error reading Pyro stdout");
						}

					}
				}

			};
			stdoutThread.start();

			// Initialize thread that will read stderr
			stderrThread = new Thread() {

				public void run() {

					while(true) {

						try {

							final String line = stdError.readLine();
							printException(null,line);

						} catch (IOException e) {

							printException(e,"Error reading Pyro stderr");

						}

					}
				}

			};
			stderrThread.start();

		} catch (final Exception e1) {

			printException(e1,"Exception starting Pyro server");

		}


	}

	@Override
	public String getTabCaption() {

		return "Burpy";
	}

	@Override
	public Component getUiComponent() {
		return mainPanel;
	}

	private void savePersistentSettings() {

		callbacks.saveExtensionSetting("pythonPath",pythonPath.getText().trim());
		callbacks.saveExtensionSetting("pyroHost",pyroHost.getText().trim());
		callbacks.saveExtensionSetting("pyroPort",pyroPort.getText().trim());
		callbacks.saveExtensionSetting("burpyPath",burpyPath.getText().trim());

	}




	@Override
	public void actionPerformed(ActionEvent event) {

		String command = event.getActionCommand();


		if(command.equals("killServer") && serverStarted) {

			stdoutThread.stop();
			stderrThread.stop();

			try {
//				pyroBurpyService.close("shutdown");
//				pyroServerProcess.destroy();
				pyroServerProcess.destroyForcibly();
				pyroBurpyService.close();
				serverStarted = false;

				SwingUtilities.invokeLater(new Runnable() {

					@Override
					public void run() {

						serverStatus.setText("");
						serverStatusButtons.setText("");
						try {
							documentServerStatus.insertString(0, "NOT running", redStyle);
							documentServerStatusButtons.insertString(0, "Server stopped", redStyle);
						} catch (BadLocationException e) {
							printException(e,"Exception setting labels");
						}

					}
				});

				printSuccessMessage("Pyro server shutted down");

			} catch (final Exception e) {

				printException(e,"Exception shutting down Pyro server");

			}


		} else if(command.equals("startServer") && !serverStarted) {

			File burpyFile = new File(burpyPath.getText().trim());
			if (burpyFile.exists()) {

				savePersistentSettings();

				try {
					
					launchPyroServer(pythonPath.getText().trim(), pythonScript);

				} catch (final Exception e) {

					printException(null, "Exception starting Pyro server");

				}
			}else {
				printException(null,"Burpy File not found!");

			}



		} else if (burpyMethods.contains(command)) {
			IHttpRequestResponse[] selectedItems = currentInvocation.getSelectedMessages();
			byte selectedInvocationContext = currentInvocation.getInvocationContext();

			try {
				// pass directly the bytes of http
				byte[] selectedRequestOrResponse = null;
				if(selectedInvocationContext == IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST || selectedInvocationContext == IContextMenuInvocation.CONTEXT_MESSAGE_VIEWER_REQUEST) {
					selectedRequestOrResponse = selectedItems[0].getRequest();
				} else {
					selectedRequestOrResponse = selectedItems[0].getResponse();
				}
				
				String ret_str = (String) pyroBurpyService.call("invoke_method", command, helpers.base64Encode(selectedRequestOrResponse));
				
				if(selectedInvocationContext == IContextMenuInvocation.CONTEXT_MESSAGE_EDITOR_REQUEST) {
					selectedItems[0].setRequest(ret_str.getBytes());
				} else {
					
					final String msg = ret_str.substring(ret_str.indexOf("\r\n\r\n")+4);
					SwingUtilities.invokeLater(new Runnable() {
						
						@Override
						public void run() {
							MessageDialog.show("Burpy "+command, msg);
						}
					});
				}

			} catch (Exception e) {

				printException(e, "Exception with custom context application");

			}
		} else if(command.equals("pythonPathSelectFile")) {

			JFrame parentFrame = new JFrame();
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setDialogTitle("Python Path");

			int userSelection = fileChooser.showOpenDialog(parentFrame);

			if(userSelection == JFileChooser.APPROVE_OPTION) {

				final File pythonPathFile = fileChooser.getSelectedFile();

				SwingUtilities.invokeLater(new Runnable() {

					@Override
					public void run() {
						pythonPath.setText(pythonPathFile.getAbsolutePath());
					}

				});

			}

		} else if(command.equals("burpyPathSelectFile")) {

			JFrame parentFrame = new JFrame();
			JFileChooser fileChooser = new JFileChooser();
			fileChooser.setDialogTitle("Burpy PY Path");

			int userSelection = fileChooser.showOpenDialog(parentFrame);

			if(userSelection == JFileChooser.APPROVE_OPTION) {

				final File burpyPathFile = fileChooser.getSelectedFile();

				SwingUtilities.invokeLater(new Runnable() {

					@Override
					public void run() {
						burpyPath.setText(burpyPathFile.getAbsolutePath());
					}

				});

			}

		} else if(command.startsWith("clearConsole")) {

			SwingUtilities.invokeLater(new Runnable() {

				@Override
				public void run() {
					String newConsoleText = "<font color=\"green\">";
					newConsoleText = newConsoleText + "<b>**** Console cleared successfully ****</b><br/>";
					newConsoleText = newConsoleText + "</font><br/>";

					pluginConsoleTextArea.setText(newConsoleText);

				}

			});

		} else if(command.startsWith("reloadScript")){
			if(serverStarted) {
				stdoutThread.stop();
				stderrThread.stop();

				try {
//					pyroBurpyService.call("shutdown");
					pyroServerProcess.destroyForcibly();
					pyroBurpyService.close();
					serverStarted = false;

					SwingUtilities.invokeLater(new Runnable() {

						@Override
						public void run() {

							serverStatus.setText("");
							serverStatusButtons.setText("");
							try {
								documentServerStatus.insertString(0, "NOT running", redStyle);
								documentServerStatusButtons.insertString(0, "Server stopped", redStyle);
							} catch (BadLocationException e) {
								printException(e, "Exception setting labels");
							}

						}
					});

					printSuccessMessage("Pyro server shutted down");


				} catch (final Exception e) {

					printException(e, "Exception shutting down Pyro server");

				}
			}

			try {

				launchPyroServer(pythonPath.getText().trim(),pythonScript);
				getMethods();

			} catch (final Exception e) {

				printException(null,"Exception starting Pyro server");

			}

		}

	}

	@Override
	public List<JMenuItem> createMenuItems(IContextMenuInvocation invocation) {

			currentInvocation = invocation;

			List<JMenuItem> menu = new ArrayList<JMenuItem>();


			
			this.burpyMethods.forEach(method_name -> {
				JMenuItem item = new JMenuItem("Burpy " + method_name);
				item.setActionCommand(method_name);
				item.addActionListener(this);
				menu.add(item);
			});

			return menu;

	}

	static String byteArrayToHexString(byte[] raw) {
		StringBuilder sb = new StringBuilder(2 + raw.length * 2);
		for (int i = 0; i < raw.length; i++) {
			sb.append(String.format("%02X", Integer.valueOf(raw[i] & 0xFF)));
		}
		return sb.toString();
	}

	private static byte[] hexStringToByteArray(String hex) {
		byte[] b = new byte[hex.length() / 2];
		for (int i = 0; i < b.length; i++){
			int index = i * 2;
			int v = Integer.parseInt(hex.substring(index, index + 2), 16);
			b[i] = (byte)v;
		}
		return b;
	}
	static String strToHexStr(String str) {
		char[] chars = "0123456789ABCDEF".toCharArray();
		StringBuilder sb = new StringBuilder("");
		byte[] bs = str.getBytes();
		int bit;
		for (int i=0; i<bs.length; i++) {
			bit = (bs[i] & 0x0f0) >>4;
			sb.append(chars[bit]);
			bit = bs[i] & 0x0f;
			sb.append(chars[bit]);
		}
		return sb.toString().trim();

	}


	@Override
	public void mouseClicked(MouseEvent e) {

	}

	@Override
	public void mousePressed(MouseEvent e) {

	}

	@Override
	public void mouseReleased(MouseEvent e) {

	}

	@Override
	public void mouseEntered(MouseEvent e) {
	}

	@Override
	public void mouseExited(MouseEvent e) {
	}

	public void printSuccessMessage(final String message) {

		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {

				String oldConsoleText = pluginConsoleTextArea.getText();

				Pattern p = Pattern.compile("^.*<body>(.*)</body>.*$", Pattern.DOTALL);
				Matcher m = p.matcher(oldConsoleText);

				String newConsoleText = "";
				if(m.find()) {
					newConsoleText = m.group(1);
				}

				if(lastPrintIsJS) {
					newConsoleText = newConsoleText + "<br/>";
				}

				newConsoleText = newConsoleText + "<font color=\"green\">";
				newConsoleText = newConsoleText + "<b>" + message + "</b><br/>";
				newConsoleText = newConsoleText + "</font><br/>";

				pluginConsoleTextArea.setText(newConsoleText);

				lastPrintIsJS = false;

			}

		});

	}


	public void printJSMessage(final String message) {

		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {

				String oldConsoleText = pluginConsoleTextArea.getText();
				Pattern p = Pattern.compile("^.*<body>(.*)</body>.*$", Pattern.DOTALL);
				Matcher m = p.matcher(oldConsoleText);

				String newConsoleText = "";
				if(m.find()) {
					newConsoleText = m.group(1);
				}

				newConsoleText = newConsoleText + "<font color=\"black\"><pre>";
				//newConsoleText = newConsoleText + message + "<br/>";
				newConsoleText = newConsoleText + message;
				newConsoleText = newConsoleText + "</pre></font>";

				pluginConsoleTextArea.setText(newConsoleText);

				lastPrintIsJS = true;

			}

		});

	}


	public void printException(final Exception e, final String message) {

		SwingUtilities.invokeLater(new Runnable() {

			@Override
			public void run() {


				String oldConsoleText = pluginConsoleTextArea.getText();
				Pattern p = Pattern.compile("^.*<body>(.*)</body>.*$", Pattern.DOTALL);
				Matcher m = p.matcher(oldConsoleText);

				String newConsoleText = "";
				if(m.find()) {
					newConsoleText = m.group(1);
				}

				if(lastPrintIsJS) {
					newConsoleText = newConsoleText + "<br/>";
				}

				newConsoleText = newConsoleText + "<font color=\"red\">";
				newConsoleText = newConsoleText + "<b>" + message + "</b><br/>";

				if(e != null) {
					newConsoleText = newConsoleText + e.toString() + "<br/>";
					//consoleText = consoleText + e.getMessage() + "<br/>";
					StackTraceElement[] exceptionElements = e.getStackTrace();
					for(int i=0; i< exceptionElements.length; i++) {
						newConsoleText = newConsoleText + exceptionElements[i].toString() + "<br/>";
					}
				}

				newConsoleText = newConsoleText + "</font><br/>";

				pluginConsoleTextArea.setText(newConsoleText);

				lastPrintIsJS = false;

			}

		});

	}


	@Override
	public void extensionUnloaded() {

		if(serverStarted) {

			stdoutThread.stop();
			stderrThread.stop();

			try {

				Runtime rt = Runtime.getRuntime();


				pyroBurpyService.call("shutdown");
				pyroServerProcess.destroyForcibly();
				pyroBurpyService.close();

				printSuccessMessage("Pyro server shutted down");

			} catch (final Exception e) {

				printException(e,"Exception shutting down Pyro server");

			}

		}

	}

	@Override
	public String getProcessorName()
	{
		return "Burpy processor";
	}

	@Override
	public byte[] processPayload(byte[] currentPayload, byte[] originalPayload, byte[] baseValue)
	{
		byte[] ret = currentPayload;
		if(should_pro){

			try {
				final String s = (String) (pyroBurpyService.call("invoke_method", "processor", new String(currentPayload)));
				ret = s.getBytes();
			} catch (Exception e) {
				stderr.println(e.toString());
				StackTraceElement[] exceptionElements = e.getStackTrace();
				for (int i = 0; i < exceptionElements.length; i++) {
					stderr.println(exceptionElements[i].toString());
				}
			}
		}

		return ret;
	}

	@Override
	public void processHttpMessage(int toolFlag, boolean messageIsRequest, IHttpRequestResponse messageInfo) {
		List<String> headers = null;
		if (should_auto) {

			if (toolFlag == IBurpExtenderCallbacks.TOOL_SCANNER ||
					toolFlag == IBurpExtenderCallbacks.TOOL_REPEATER ||
					toolFlag == IBurpExtenderCallbacks.TOOL_INTRUDER) {
				if (messageIsRequest) {

					byte[] request = messageInfo.getRequest();

						String ret = "";
						try {
							ret = (String) pyroBurpyService.call("invoke_method", "encrypt", helpers.base64Encode(request));
						} catch(Exception e) {
							stderr.println(e.toString());
							StackTraceElement[] exceptionElements = e.getStackTrace();
							for(int i=0; i< exceptionElements.length; i++) {
								stderr.println(exceptionElements[i].toString());
							}
						}

						IRequestInfo nreqInfo = helpers.analyzeRequest(ret.getBytes());
						headers = nreqInfo.getHeaders();
						int nbodyOff = nreqInfo.getBodyOffset();
						byte[] nbody = ret.substring(nbodyOff).getBytes();

						byte[] newRequest = helpers.buildHttpMessage(headers, nbody); //

						messageInfo.setRequest(newRequest);

				}else {

						// Get response bytes
					byte[] response = messageInfo.getResponse();
					
					String ret = "";
					try {
						ret = (String) pyroBurpyService.call("invoke_method", "decrypt", helpers.base64Encode(response));
						stderr.println(ret);
					} catch(Exception e) {
						stderr.println(e.toString());
						StackTraceElement[] exceptionElements = e.getStackTrace();
						for(int i=0; i< exceptionElements.length; i++) {
							stderr.println(exceptionElements[i].toString());
						}
					}
					IResponseInfo nresInfo = helpers.analyzeResponse(ret.getBytes());
					int nbodyOff = nresInfo.getBodyOffset();
					byte[] nbody = ret.substring(nbodyOff).getBytes();
					headers = nresInfo.getHeaders();
					byte[] newResponse = helpers.buildHttpMessage(headers, nbody);
					messageInfo.setResponse(newResponse);
				}

			}

		}

	}

	@Override
	public IMessageEditorTab createNewInstance(IMessageEditorController controller, boolean editable) {
		return new iMessageEditorTab(controller, editable);
	}

	public class iMessageEditorTab implements IMessageEditorTab {

		private IMessageEditorController controller;
		private ITextEditor iTextEditor = callbacks.createTextEditor();
		private byte[] currentMessage;


		public iMessageEditorTab(IMessageEditorController controller, boolean editable) {
			this.controller = controller;
		}


		@Override
		public String getTabCaption() {
			return "BurpyTab";
		}

		@Override
		public Component getUiComponent() {
			return iTextEditor.getComponent();
		}

		@Override
		public boolean isEnabled(byte[] content, boolean isRequest) {
			return true;
		}

		@Override
		public void setMessage(byte[] content, boolean isRequest) {

			String ret = "";
			try {
				ret = (String) pyroBurpyService.call("invoke_method", "decrypt", helpers.base64Encode(content));
			} catch(Exception e) {
				stderr.println(e.toString());
				StackTraceElement[] exceptionElements = e.getStackTrace();
				for(int i=0; i< exceptionElements.length; i++) {
					stderr.println(exceptionElements[i].toString());
				}
			}
			iTextEditor.setText(ret.getBytes(StandardCharsets.UTF_8));

			currentMessage = ret.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		public byte[] getMessage() {
			if (iTextEditor.isTextModified()){
				byte[] data = iTextEditor.getText();
				String ret = "";
				try {
					ret = (String) pyroBurpyService.call("invoke_method", "encrypt", helpers.base64Encode(data));
				} catch(Exception e) {
					stderr.println(e.toString());
					StackTraceElement[] exceptionElements = e.getStackTrace();
					for(int i=0; i< exceptionElements.length; i++) {
						stderr.println(exceptionElements[i].toString());
					}
				}

				return ret.getBytes(StandardCharsets.UTF_8);
			} else {
				return currentMessage;
			}
		}

		@Override
		public boolean isModified() {
			return iTextEditor.isTextModified();
		}

		@Override
		public byte[] getSelectedData() {
			return iTextEditor.getSelectedText();
		}
	}



	public static void main(String[] args) throws PyroException, java.io.IOException {
		// for testing purpose
		System.out.println("Initializing service");
//		NameServerProxy ns = NameServerProxy.locateNS(null);
		PyroProxy service = null;
		try {
			service = new PyroProxy("127.0.0.1", 10999, "BurpyServicePyro");
		}catch (PyroException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		System.out.println("Getting methods");
		try {
			Object methods_obj = service.call("get_methods", null);
			System.out.println(methods_obj.toString());
		}catch (PyroException e) {
			System.out.println("PyroException");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}
}

