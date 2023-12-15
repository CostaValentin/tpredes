import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Cliente {
    private String nombre;
    private String ip;
    private int puerto;
    private Cliente clienteAnterior;
    private Cliente clienteSiguiente;

    private static Map<String, Cliente> clientes = new HashMap<>();

    public Cliente(String archivoConfig, String archivoConex, String nombre) {
        this.nombre = nombre;
        cargarConfiguracion(archivoConfig);
        establecerClientesAnteriorYPosterior(archivoConex);
        iniciarPrograma();
    }

    public Cliente(String nombre, String ip, int puerto,Cliente clienteAnterior,Cliente clienteSiguiente) {
        this.nombre = nombre;
        this.ip = ip;
        this.puerto = puerto;
        this.clienteAnterior = clienteAnterior;
        this.clienteSiguiente = clienteSiguiente;
    }

    private void agregarCliente(String nombre, Cliente cliente) {
        clientes.put(nombre, cliente);
    }

    public Cliente pasarAtributos(String[] atributos) {
        if (atributos.length == 3) {
            int puerto = Integer.parseInt(atributos[2]);
            return new Cliente(atributos[0], atributos[1], puerto, null, null);
        }
        return null;
    }

    public void iniciarPrograma() {
        ExecutorService executorService = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(clientes.get(this.getNombre()).getPuerto())) {
            System.out.println("Escuchando en " + clientes.get(this.getNombre()).getIp() + ":" + clientes.get(this.getNombre()).getPuerto());

            executorService.execute(() -> {
                try {
                    while (!Thread.interrupted()) {
                        Socket socket = serverSocket.accept();
                        BufferedReader entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                        executorService.execute(() -> escucharMensajes(entrada));
                    }
                } catch (IOException e) {
                    System.err.println("Error al escuchar conexiones: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            Scanner scanner = new Scanner(System.in);
            while (true) {
                System.out.print("Ingrese destinatario: ");
                String destinatario = scanner.nextLine();

                if (clientes.containsKey(destinatario) && !(destinatario.equals(this.getNombre()))) {
                    System.out.print("Ingrese mensaje: ");
                    String mensaje = scanner.nextLine();
                    enviarMensaje(destinatario, mensaje);
                } else {
                    System.out.println("Destinatario inexistente");
                }
            }
        } catch (IOException e) {
            System.err.println("Error al inicializar el servidor: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Apagar el ExecutorService cuando ya no se necesite
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }

    public void establecerClientesAnteriorYPosterior(String connectionFile) {
        try (BufferedReader br = new BufferedReader(new FileReader(connectionFile))) {
            Map<String, Cliente> clientesOrdenados = new LinkedHashMap<>();

            String line;
            while ((line = br.readLine()) != null) {
                String[] nombresClientes = line.split("<->");
                for (String nombre : nombresClientes) {
                    Cliente cliente = clientes.get(nombre);
                    clientesOrdenados.put(nombre, cliente);
                }
            }

            Cliente clienteAnterior = null;
            for (Cliente clienteActual : clientesOrdenados.values()) {
                clienteActual.setClienteAnterior(clienteAnterior);
                if (clienteAnterior != null) {
                    clienteAnterior.setClienteSiguiente(clienteActual);
                }
                clienteAnterior = clienteActual;

                clientes.put(clienteActual.getNombre(), clienteActual);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private void cargarConfiguracion(String configFile) {
        try (BufferedReader br = new BufferedReader(new FileReader(configFile))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                String[] partes = linea.split(":");
                if (partes.length == 3) {
                    Cliente esteCliente = pasarAtributos(partes);
                    agregarCliente(esteCliente.getNombre(), esteCliente);
                } else {
                    System.out.println("Error en el formato de la linea: " + linea );
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public interface ClienteFunction {
        Cliente apply(Cliente cliente);
    }

// ...

    private void comunicacionGenerico(Cliente clienteOrigen, Cliente clienteDestino, String mensaje, ClienteFunction obtenerSiguiente) {
        Cliente este = clientes.get(clienteOrigen.getNombre());
        Cliente clienteIntermediario = obtenerSiguiente.apply(este);

        while (clienteIntermediario != null && !clienteIntermediario.equals(clienteDestino)) {
            if (!clienteIntermediario.equals(clienteDestino.getClienteAnterior()) &&
                    !clienteIntermediario.equals(clienteDestino.getClienteSiguiente())) {
                enviarMensajeDirecto(clienteIntermediario, clienteDestino, mensaje);
                System.out.println("REENVIANDO A " + clienteIntermediario.getNombre() +
                        " PARA QUE LLEGUE A: " + clienteDestino.getNombre() +
                        ": " + clienteOrigen.getNombre() + " | " + mensaje);
            }
            clienteIntermediario = obtenerSiguiente.apply(clienteIntermediario);
        }

        if (clienteIntermediario != null || clienteIntermediario.equals(clienteDestino)) {
            enviarMensajeDirecto(clienteIntermediario, clienteDestino, mensaje);
        }
    }

    private void comunicacionAtras(Cliente clienteOrigen, Cliente clienteDestino, String mensaje) {
        comunicacionGenerico(clienteOrigen, clienteDestino, mensaje, new ClienteFunction() {
            @Override
            public Cliente apply(Cliente cliente) {
                return cliente.getClienteAnterior();
            }
        });
    }

    private void comunicacionSiguiente(Cliente clienteOrigen, Cliente clienteDestino, String mensaje) {
        comunicacionGenerico(clienteOrigen, clienteDestino, mensaje, new ClienteFunction() {
            @Override
            public Cliente apply(Cliente cliente) {
                return cliente.getClienteSiguiente();
            }
        });
    }


    private void enviarMensajeDirecto(Cliente remitente,Cliente clienteDestino, String mensaje) {
        remitente = clientes.get(remitente.getNombre());
        try (Socket socketDestino = new Socket(clienteDestino.getIp(), clienteDestino.getPuerto());
             PrintWriter salidaDestino = new PrintWriter(socketDestino.getOutputStream(), true)) {

            salidaDestino.println( remitente.getNombre() + " : "  + mensaje);
            System.out.println("Mensaje enviado a: " + clienteDestino.getNombre() );

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    public void enviarMensaje(String destinatario, String mensaje) {
        Cliente esteCliente = clientes.get(this.getNombre());
        Cliente clienteDestino = clientes.get(destinatario);

        if (clienteDestino == null) {
            System.out.println("Destinatario inexistente");
            return;
        }

        if (esClienteSiguienteOAnterior(clienteDestino)) {
            enviarMensajeDirecto(esteCliente, clienteDestino, mensaje);
        } else {
            reenviarMensaje(esteCliente, clienteDestino, mensaje);
        }
    }

    private boolean esClienteSiguienteOAnterior(Cliente clienteDestino) {
        Cliente clienteSiguiente = this.getClienteSiguiente();
        Cliente clienteAnterior = this.getClienteAnterior();

        return clienteDestino.equals(clienteSiguiente) || clienteDestino.equals(clienteAnterior);
    }

    private void reenviarMensaje(Cliente clienteOrigen, Cliente clienteDestino, String mensaje) {
        clienteOrigen = clientes.get(clienteOrigen.getNombre());
        clienteDestino = clientes.get(clienteDestino.getNombre());

        List<Cliente> listaClientes = new ArrayList<>(clientes.values());

        // Harcodear el orden de los clientes
        List<Cliente> listaOrdenada = new ArrayList<>();
        listaOrdenada.add(getClientePorNombre(listaClientes, "JUAN"));
        listaOrdenada.add(getClientePorNombre(listaClientes, "JOSE"));
        listaOrdenada.add(getClientePorNombre(listaClientes, "CARLOS"));

        int indiceOrigen = listaOrdenada.indexOf(clienteOrigen);
        int indiceDestino = listaOrdenada.indexOf(clienteDestino);

        if (indiceDestino > indiceOrigen) { // izq a derecha
            comunicacionSiguiente(clienteOrigen, clienteDestino, mensaje);
        } else if (indiceDestino < indiceOrigen) { // derecha a izq
            comunicacionAtras(clienteOrigen, clienteDestino, mensaje);
        } else {
            System.out.println("Error: Son iguales.");
        }
    }

    // Método para obtener un cliente por nombre
    private Cliente getClientePorNombre(List<Cliente> clientes, String nombre) {
        for (Cliente cliente : clientes) {
            if (cliente.getNombre().equals(nombre)) {
                return cliente;
            }
        }
        return null; // Manejar el caso en que el cliente no sea encontrado
    }
    private int indexOfClient(String connectionFile, String clienteNombre) {
        try (BufferedReader br = new BufferedReader(new FileReader(connectionFile))) {
            String linea;
            int index = 0;
            while ((linea = br.readLine()) != null) {
                String[] nombresClientes = linea.split("<->");
                for (String nombre : nombresClientes) {
                    if (nombre.equalsIgnoreCase(clienteNombre)) {
                        return index;
                    }
                    index++;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return -1;
    }


    private void escucharMensajes(BufferedReader entrada) {
        try {
            while (true) {
                String mensajeRecibido = entrada.readLine();
                if (mensajeRecibido != null) {
                    if (mensajeRecibido.contains("a traves de intermediario/s")) {
                        System.out.println("Mensaje recibido a traves de intermediario.");
                    } else {
                        System.out.println("Mensaje recibido: " + mensajeRecibido);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPuerto() {
        return puerto;
    }

    public void setPuerto(int puerto) {
        this.puerto = puerto;
    }

    public Cliente getClienteAnterior() {
        return clienteAnterior;
    }

    public void setClienteAnterior(Cliente clienteAnterior) {
        this.clienteAnterior = clienteAnterior;
    }

    public Cliente getClienteSiguiente() {
        return clienteSiguiente;
    }

    public void setClienteSiguiente(Cliente clienteSiguiente) {
        this.clienteSiguiente = clienteSiguiente;
    }

    public static Map<String, Cliente> getClientes() {
        return clientes;
    }

    public static void setClientes(Map<String, Cliente> clientes) {
        Cliente.clientes = clientes;
    }


}