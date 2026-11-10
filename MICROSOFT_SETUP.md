# Configuración de verificación premium real (offline-mode)

SpigotAuth no puede demostrar la propiedad de una cuenta consultando únicamente el nombre en Mojang. Para verificar al propietario usa Microsoft Device Authorization, Xbox Live, XSTS y Minecraft Services.

## 1. Registrar una aplicación Microsoft

1. Abre https://entra.microsoft.com/ e inicia sesión con una cuenta Microsoft.
2. Ve a **App registrations** y crea una aplicación.
3. En **Supported account types**, permite cuentas personales de Microsoft (Minecraft).
4. En **Authentication**, habilita **Allow public client flows** si aparece esa opción.
5. Copia el **Application (client) ID**. No crees ni compartas un client secret: el flujo de dispositivo es para clientes públicos y este plugin no necesita guardar secretos.

La disponibilidad de Xbox Live para la aplicación y la cuenta depende de Microsoft. Si Microsoft rechaza el permiso `XboxLive.signin`, revisa la configuración y las restricciones de la aplicación en Entra.

## 2. Configurar SpigotAuth

En `plugins/SpigotAuth/config.yml`, cambia:

```yaml
microsoft-client-id: "PON_AQUI_TU_CLIENT_ID"
login-timeout-seconds: 600
```

Pega el Application (client) ID real, guarda el archivo y reinicia el servidor.

## 3. Cómo inicia sesión un jugador

En servidores `online-mode=false`, el jugador ejecuta:

```
/premiumlogin
```

El plugin muestra un enlace oficial de Microsoft y un código temporal. El jugador introduce el código en Microsoft, autoriza la aplicación y el plugin comprueba el perfil de Minecraft Java devuelto por Minecraft Services. Solo inicia sesión si el nombre del perfil verificado coincide con el nombre que está usando en el servidor.

La autorización debe repetirse en cada conexión. No se guarda el token ni se confía en el nombre/UUID offline para futuras sesiones: cualquiera podría suplantarlos.

## Seguridad y limitaciones

- No se almacena la contraseña Microsoft, el access token ni el device code.
- Las llamadas de red se ejecutan fuera del hilo principal del servidor.
- Una cuenta sin perfil Java, una cuenta distinta, una autorización cancelada o un error de API no obtiene acceso premium.
- `/login` sigue disponible como método local alternativo si el jugador tiene una cuenta registrada.
- Para redes públicas grandes, se recomienda usar `online-mode=true` o un proxy correctamente configurado con forwarding moderno y seguro.