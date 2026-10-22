package com.example.questmanager

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Talks to this headset's own adbd over Wireless debugging (localhost). No PC needed. */
class AdbManager(ctx: Context) : AbsAdbConnectionManager() {
    private val priv: PrivateKey
    private val cert: Certificate

    init {
        setApi(android.os.Build.VERSION.SDK_INT)
        val kf = File(ctx.filesDir, "adb.key"); val cf = File(ctx.filesDir, "adb.crt")
        if (kf.exists() && cf.exists()) {
            priv = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=QuestManager")
            val b = JcaX509v3CertificateBuilder(name, BigInteger.ONE, Date(0),
                Date(System.currentTimeMillis() + 10L * 365 * 86400 * 1000), name, kp.public)
            cert = JcaX509CertificateConverter().getCertificate(
                b.build(JcaContentSignerBuilder("SHA512withRSA").build(kp.private)))
            priv = kp.private
            kf.writeBytes(priv.encoded); cf.writeBytes(cert.encoded)
        }
    }
    override fun getPrivateKey() = priv
    override fun getCertificate() = cert
    override fun getDeviceName() = "QuestManager"

    fun run(cmd: String): String =
        openStream("shell:$cmd").use { it.openInputStream().bufferedReader().readText() }
}
