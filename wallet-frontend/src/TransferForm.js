import axios from "axios";
import { useState } from "react";

function TransferForm({ onTransferSuccess }) {
    const [receiverId, setReceiverId] = useState("");
    const [amount, setAmount] = useState("");

    const submit = async (e) => {
        e.preventDefault();
        await axios.post("http://localhost:8080/api/accounts/transfer")
    }
}