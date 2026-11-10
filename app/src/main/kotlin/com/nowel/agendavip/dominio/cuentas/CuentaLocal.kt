package com.nowel.agendavip.dominio.cuentas

/**
 * Reúne los datos públicos de una cuenta que solo existe en este teléfono.
 * Recibe su UUID, el usuario legible, el nombre inicial del perfil y la fecha de creación.
 * UUID significa identificador aleatorio único; distingue archivos sin usar nombres personales.
 * El repositorio devuelve esta información al acceso; nunca contiene la contraseña ni su resumen.
 * El nombre inicial sirve para preparar el perfil una vez, sin sustituir sus cambios posteriores.
 */
data class CuentaLocal(
    val id: String,
    val usuario: String,
    val nombreInicial: String,
    val creadaEn: Long,
)
