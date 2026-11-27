package com.carlink.link

import java.net.Inet4Address
import java.net.InetAddress

/**
 * Where the adapter is on its own Wi-Fi (the AP link, the plan's ADDENDUM 24 item 3): it is that network's gateway, the
 * DHCP server that gave the head unit its address. No address is typed and none is fixed here: the adapter's own
 * `/etc/wifi_ip` decides it, and Android reports it ([WifiBoxFinder.gateways]).
 */
object WifiGateway {
    private const val BROADCAST = "255.255.255.255"

    /**
     * The addresses to try, in order: the network's DHCP server ([dhcpServer], Android's `LinkProperties
     * .getDhcpServerAddress()`), then the gateways of its default routes ([defaultGateways]) that differ; IPv4 unicast only.
     */
    fun addresses(
        dhcpServer: InetAddress?,
        defaultGateways: List<InetAddress>,
    ): List<String> = (listOfNotNull(dhcpServer) + defaultGateways).filter(::usable).mapNotNull { it.hostAddress }.distinct()

    /** An IPv4 unicast address a TCP connect can reach: not 0.0.0.0, loopback, link-local, multicast or the broadcast. */
    fun usable(a: InetAddress): Boolean =
        a is Inet4Address &&
            !a.isAnyLocalAddress &&
            !a.isLoopbackAddress &&
            !a.isLinkLocalAddress &&
            !a.isMulticastAddress &&
            a.hostAddress != BROADCAST
}
